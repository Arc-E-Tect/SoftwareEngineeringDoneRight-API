"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const YAML = require("yaml");

const { init, scaffold, FILES, DEFAULTS } = require("../src/init");
const { loadFrom } = require("../src/config");
const { versionOf } = require("../src/bundle-version");

function tmpdir() {
    return fs.mkdtempSync(path.join(os.tmpdir(), "aop-init-"));
}

function walk(dir) {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
        e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]);
}

const OPENAPI = { ...DEFAULTS, kinds: ["openapi"] };
const ASYNCAPI = { ...DEFAULTS, kinds: ["asyncapi"] };
const BOTH = { ...DEFAULTS, kinds: ["openapi", "asyncapi"] };

test("the defaults write the scaffold init always wrote, without the asyncapi keys it never used", () => {
    // test/fixtures/init-defaults is the scaffold as it was before init asked anything,
    // with only apionly.yaml changed: it no longer declares an AsyncAPI tree it does not create.
    const expected = path.join(__dirname, "fixtures", "init-defaults");
    const files = scaffold(DEFAULTS);
    assert.deepStrictEqual(Object.keys(files).sort(),
        walk(expected).map((f) => path.relative(expected, f).split(path.sep).join("/")).sort());
    for (const [rel, content] of Object.entries(files)) {
        assert.strictEqual(content, fs.readFileSync(path.join(expected, rel), "utf8"), rel);
    }
    assert.deepStrictEqual(FILES, files);
});

test("scaffolds every file it declares", () => {
    const dir = tmpdir();
    const { created } = init(dir);
    assert.deepStrictEqual(created.sort(), Object.keys(FILES).sort());
    for (const rel of Object.keys(FILES)) {
        assert.ok(fs.existsSync(path.join(dir, rel)), `${rel} was not written`);
    }
});

test("an OpenAPI-only scaffold has no asyncapi keys and no AsyncAPI tree", () => {
    const files = scaffold(OPENAPI);
    const config = YAML.parse(files["apionly.yaml"]);
    assert.ok(!files["apionly.yaml"].includes("asyncapi"));
    assert.deepStrictEqual(Object.keys(config.sources), ["root", "openapi"]);
    assert.ok(Object.keys(files).every((rel) => !rel.startsWith("specs/asyncapi/")));
    assert.ok(files[".redocly.yaml"]);
});

test("an AsyncAPI-only scaffold has no openapi keys, no OpenAPI tree and no Redocly configuration", () => {
    const files = scaffold(ASYNCAPI);
    const config = YAML.parse(files["apionly.yaml"]);
    assert.deepStrictEqual(Object.keys(config.sources), ["root", "asyncapi"]);
    assert.deepStrictEqual(Object.keys(config.defaults).filter((k) => k !== "placeholders"), ["asyncapi"]);
    assert.deepStrictEqual(Object.keys(config.toolchain), ["asyncapi"]);
    assert.deepStrictEqual(Object.keys(config.targets["example-service"]), ["asyncapi"]);
    assert.ok(!/^\s*openapi:/m.test(files["apionly.yaml"]));
    assert.ok(Object.keys(files).every((rel) => !rel.startsWith("specs/openapi/")));
    assert.strictEqual(files[".redocly.yaml"], undefined);
    // Its version file sits beside its first -- here its only -- bundle root.
    assert.ok(files["specs/asyncapi/bundles/example-service.bundle.properties"]);
});

test("a scaffold of both kinds declares both, and one event payload refers to an OpenAPI schema", () => {
    const files = scaffold(BOTH);
    const config = YAML.parse(files["apionly.yaml"]);
    assert.deepStrictEqual(Object.keys(config.targets["example-service"]), ["openapi", "asyncapi"]);
    assert.ok(files["specs/openapi/bundles/example-service.bundle.properties"]);
    assert.strictEqual(files["specs/asyncapi/bundles/example-service.bundle.properties"], undefined);

    const shared = "specs/openapi/components/common/schemas/ExampleIdV1.yaml";
    assert.ok(files[shared]);
    const payload = Object.entries(files).find(([rel]) => rel.startsWith("specs/asyncapi/components/"));
    assert.match(payload[1], /\$ref: '\.\.\/\.\.\/\.\.\/\.\.\/openapi\/components\/common\/schemas\/ExampleIdV1\.yaml'/);
    // One definition, two protocols: the HTTP side uses the same schema.
    assert.match(files["specs/openapi/paths/example/ExamplesV1.yaml"], /common\/schemas\/ExampleIdV1\.yaml/);
});

for (const [name, values] of [["OpenAPI", OPENAPI], ["AsyncAPI", ASYNCAPI], ["both", BOTH]]) {
    test(`every $ref in the ${name} scaffold resolves to a file that exists, and its configuration loads`, () => {
        const dir = tmpdir();
        init(dir, { values });
        let checked = 0;
        for (const file of walk(path.join(dir, "specs")).filter((f) => f.endsWith(".yaml"))) {
            for (const match of fs.readFileSync(file, "utf8").matchAll(/\$ref:\s*'([^']+)'/g)) {
                if (match[1].startsWith("#")) continue;
                assert.ok(fs.existsSync(path.resolve(path.dirname(file), match[1])), `${file} references missing ${match[1]}`);
                checked += 1;
            }
        }
        assert.ok(checked > 0, "expected the scaffold to contain $refs");
        const config = loadFrom(dir);
        for (const kind of values.kinds) {
            assert.deepStrictEqual(config.targetsFor(kind), ["example-service"]);
        }
        assert.match(versionOf(config, "example-service"), /^\d+\.\d+\.\d+$/);
    });
}

test("the values chosen are what the scaffold says", () => {
    const values = {
        ...BOTH, target: "customer-orders", title: "Orders API", contractVersion: "2.0.0",
        contactName: "Orders Team", contactUrl: "https://orders.example.com/team", license: "MIT",
        licenseUrl: "https://opensource.org/license/mit", serverUrl: "https://api.orders.example.com",
        brokerHost: "broker.example.com:9092",
    };
    const files = scaffold(values);
    const info = YAML.parse(files["specs/openapi/shared/info.yaml"]);
    assert.strictEqual(info.title, "Orders API");
    assert.deepStrictEqual(info.contact, { name: "Orders Team", url: "https://orders.example.com/team" });
    assert.deepStrictEqual(info.license, { name: "MIT", url: "https://opensource.org/license/mit" });
    assert.strictEqual(YAML.parse(files["specs/openapi/shared/servers.yaml"])[0].url, "https://api.orders.example.com");
    assert.match(files["specs/openapi/bundles/customer-orders.bundle.properties"], /^version=2\.0\.0$/m);
    assert.ok(files["specs/openapi/bundles/customer-orders_openapi_structure.yaml"]);

    const async = YAML.parse(files["specs/asyncapi/bundles/customer-orders_asyncapi_structure.yaml"]);
    assert.strictEqual(async.info.title, "Orders API");
    assert.deepStrictEqual(async.info.license, { name: "MIT", url: "https://opensource.org/license/mit" });
    assert.strictEqual(async.servers.production.host, "broker.example.com:9092");
    assert.deepStrictEqual(Object.keys(YAML.parse(files["apionly.yaml"]).targets), ["customer-orders"]);
});

test("a value YAML would misread is quoted, and reads back as written", () => {
    const files = scaffold({ ...OPENAPI, title: "Orders: the API #1", contactName: "'Team' \"A\"" });
    const info = YAML.parse(files["specs/openapi/shared/info.yaml"]);
    assert.strictEqual(info.title, "Orders: the API #1");
    assert.strictEqual(info.contact.name, "'Team' \"A\"");
});

test("an existing file is reported as identical, or as differing and left alone", () => {
    const dir = tmpdir();
    init(dir);
    const config = path.join(dir, "apionly.yaml");
    fs.writeFileSync(config, "# edited by hand\n");
    // Line endings and a final newline are not differences: editors change them unasked.
    const info = path.join(dir, "specs/openapi/shared/info.yaml");
    fs.writeFileSync(info, fs.readFileSync(info, "utf8").replace(/\n/g, "\r\n").replace(/\r\n$/, ""));

    const lines = [];
    const second = init(dir, { log: (line) => lines.push(line) });
    assert.deepStrictEqual(second.created, []);
    assert.deepStrictEqual(second.differing, ["apionly.yaml"]);
    assert.ok(second.identical.includes("specs/openapi/shared/info.yaml"));
    assert.strictEqual(second.identical.length, Object.keys(FILES).length - 1);
    assert.strictEqual(fs.readFileSync(config, "utf8"), "# edited by hand\n");
    assert.ok(lines.includes("  differs    apionly.yaml (left alone; --force overwrites)"));
    assert.ok(lines.includes("  identical  .gitignore"));
});

test("--force overwrites only what differs", () => {
    const dir = tmpdir();
    init(dir);
    const config = path.join(dir, "apionly.yaml");
    fs.writeFileSync(config, "# edited by hand\n");

    const lines = [];
    const forced = init(dir, { force: true, log: (line) => lines.push(line) });
    assert.deepStrictEqual(forced.overwritten, ["apionly.yaml"]);
    assert.strictEqual(fs.readFileSync(config, "utf8"), FILES["apionly.yaml"]);
    assert.ok(lines.includes("  overwrote  apionly.yaml"));
    assert.ok(!forced.identical.includes("apionly.yaml"));
});
