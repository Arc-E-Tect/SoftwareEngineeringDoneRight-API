"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { init, FILES } = require("../src/init");
const { loadFrom } = require("../src/config");

function tmpdir() {
    return fs.mkdtempSync(path.join(os.tmpdir(), "aop-init-"));
}

test("scaffolds every file it declares", () => {
    const dir = tmpdir();
    const { created } = init(dir);
    assert.deepStrictEqual(created.sort(), Object.keys(FILES).sort());
    for (const rel of Object.keys(FILES)) {
        assert.ok(fs.existsSync(path.join(dir, rel)), `${rel} was not written`);
    }
});

test("the scaffolded configuration parses and declares a buildable target", () => {
    // The point of `init` is that the conventions are reusable, not just the
    // code. A scaffold that does not load is worse than no scaffold.
    const dir = tmpdir();
    init(dir);
    const config = loadFrom(dir);
    assert.deepStrictEqual(config.targetsFor("openapi"), ["example-service"]);
    assert.strictEqual(config.outputName("openapi"), "openapi.yaml");
    assert.ok(fs.existsSync(path.join(dir, config.sources.root, config.sources.openapi, "bundles")));
});

test("every $ref in the scaffold resolves to a file that exists", () => {
    const dir = tmpdir();
    init(dir);
    const specs = path.join(dir, "specs");
    const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).flatMap((e) =>
        e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]);

    let checked = 0;
    for (const file of walk(specs).filter((f) => f.endsWith(".yaml"))) {
        for (const match of fs.readFileSync(file, "utf8").matchAll(/\$ref:\s*'([^']+)'/g)) {
            const ref = match[1];
            if (ref.startsWith("#")) continue;
            const resolved = path.resolve(path.dirname(file), ref);
            assert.ok(fs.existsSync(resolved), `${file} references missing ${ref}`);
            checked += 1;
        }
    }
    assert.ok(checked > 0, "expected the scaffold to contain $refs");
});

test("existing files are left alone unless --force", () => {
    const dir = tmpdir();
    init(dir);
    const config = path.join(dir, "apionly.yaml");
    fs.writeFileSync(config, "# edited by hand\n");

    const second = init(dir);
    assert.ok(second.skipped.includes("apionly.yaml"));
    assert.strictEqual(fs.readFileSync(config, "utf8"), "# edited by hand\n");

    init(dir, { force: true });
    assert.notStrictEqual(fs.readFileSync(config, "utf8"), "# edited by hand\n");
});
