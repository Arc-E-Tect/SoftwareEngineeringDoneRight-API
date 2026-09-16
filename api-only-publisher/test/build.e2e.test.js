"use strict";

// One real build, end to end, through the scaffold `init` produces.
//
// This is the slow test: it shells out to the pinned bundler exactly as a real
// build does. That is the point -- the bundling, linting, stamping and
// distribution steps have no meaning mocked, and a scaffold that does not build
// is a broken release however well every unit passes.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { init } = require("../src/init");
const { loadFrom } = require("../src/config");
const { build } = require("../src/pipeline");

function scaffold() {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-e2e-"));
    init(dir);
    return loadFrom(dir);
}

test("a scaffolded library builds, lints and stamps a real document", { timeout: 300000 }, () => {
    const config = scaffold();

    const results = build(config, { versionOf: () => "1.2.3" });

    assert.strictEqual(results.length, 1);
    const document = fs.readFileSync(results[0].file, "utf8");
    assert.match(document, /^ {2}version: 1\.2\.3$/m, "the version must be stamped on the built document");
    assert.match(document, /Conventions/, "the placeholder must have been substituted");
    assert.ok(!/\{\{/.test(document), "no placeholder may survive into a published contract");
});

test("building without a version leaves the source version in place", { timeout: 300000 }, () => {
    const config = scaffold();

    const results = build(config, {});

    assert.match(fs.readFileSync(results[0].file, "utf8"), /^ {2}version: 0\.0\.0$/m);
});

test("a target with no distribution configured is built but copied nowhere", { timeout: 300000 }, () => {
    // The scaffold declares no `distribution` block, which is the shape every
    // library ends up with once its consumers subscribe rather than receive.
    const config = scaffold();

    const results = build(config, { versionOf: () => "1.0.0" });

    assert.strictEqual(results[0].distributed, null);
    assert.ok(fs.existsSync(results[0].file));
});

test("an unresolved placeholder fails the build rather than shipping a marker", { timeout: 300000 }, () => {
    const config = scaffold();
    const info = path.join(config.sourceRoot(), "openapi/shared/info.yaml");
    fs.writeFileSync(info, fs.readFileSync(info, "utf8").replace("{{conventions}}", "{{nowhere}}"));

    assert.throws(() => build(config, { versionOf: () => "1.0.0" }), /no Markdown file found for \{\{nowhere\}\}/);
});

test("a bundle root that does not exist is reported against its target", { timeout: 300000 }, () => {
    const config = scaffold();
    fs.rmSync(path.join(config.sourceRoot(), "openapi/bundles/example-service_openapi_structure.yaml"));

    assert.throws(() => build(config, { versionOf: () => "1.0.0" }), /bundle root not found/);
});

// ------------------------------------------------------- x-fragment-path

const YAML = require("yaml");

function withoutStamps(text) {
    return text.split("\n").filter((line) => !/^\s*x-fragment-path: /.test(line)).join("\n");
}

test("every hoisted component carries the path of the fragment it came from, and nothing else changes", { timeout: 300000 }, () => {
    const stamped = scaffold();
    const plain = scaffold();
    fs.writeFileSync(plain.path, fs.readFileSync(plain.path, "utf8")
        .replace("    outputName: openapi.yaml", "    outputName: openapi.yaml\n    fragmentPaths: false"));
    const plainConfig = loadFrom(plain.root);

    const withPaths = fs.readFileSync(build(stamped, { versionOf: () => "1.0.0" })[0].file, "utf8");
    const withoutPaths = fs.readFileSync(build(plainConfig, { versionOf: () => "1.0.0" })[0].file, "utf8");

    const components = YAML.parse(withPaths).components;
    const found = [];
    for (const [type, entries] of Object.entries(components)) {
        for (const [name, value] of Object.entries(entries)) {
            const fragment = value["x-fragment-path"];
            assert.ok(fragment, `${type}/${name} carries no x-fragment-path`);
            assert.ok(fs.existsSync(path.join(stamped.sourceRoot(), fragment)), `${fragment} is not a file in the library`);
            found.push(`${type}/${name}=${fragment}`);
        }
    }
    assert.deepStrictEqual(found.sort(), [
        "responses/InvalidRequestProblemV1=openapi/components/common/responses/errors/InvalidRequestProblemV1.yaml",
        "securitySchemes/bearerAuth=openapi/components/common/security/BearerAuth.yaml",
    ]);

    assert.ok(!withoutPaths.includes("x-fragment-path"));
    assert.strictEqual(withoutStamps(withPaths), withoutPaths);
});

test("a component fragment that is also inlined elsewhere fails the build rather than stamping the copy", { timeout: 300000 }, () => {
    const config = scaffold();
    const info = path.join(config.sourceRoot(), "openapi/shared/info.yaml");
    fs.appendFileSync(info, "x-auth:\n  $ref: '../components/common/security/BearerAuth.yaml'\n");

    assert.throws(() => build(config, { versionOf: () => "1.0.0" }),
        /x-fragment-path.*\/info\/x-auth.*openapi\/components\/common\/security\/BearerAuth\.yaml/s);
});

test("a fragment that sets x-fragment-path itself fails the build, against its target", { timeout: 300000 }, () => {
    const config = scaffold();
    const scheme = path.join(config.sourceRoot(), "openapi/components/common/security/BearerAuth.yaml");
    fs.writeFileSync(scheme, "x-fragment-path: elsewhere.yaml\n" + fs.readFileSync(scheme, "utf8"));

    assert.throws(() => build(config, { versionOf: () => "1.0.0" }), (error) =>
        error.name === "Error" && error.constructor.name === "BuildError" &&
        /target 'example-service': openapi\/components\/common\/security\/BearerAuth\.yaml declares x-fragment-path/.test(error.message));
});
