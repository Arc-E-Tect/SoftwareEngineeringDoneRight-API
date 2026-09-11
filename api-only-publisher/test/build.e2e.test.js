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

    const results = build(config, { version: "1.2.3" });

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

    const results = build(config, { version: "1.0.0" });

    assert.strictEqual(results[0].distributed, null);
    assert.ok(fs.existsSync(results[0].file));
});

test("an unresolved placeholder fails the build rather than shipping a marker", { timeout: 300000 }, () => {
    const config = scaffold();
    const info = path.join(config.sourceRoot(), "openapi/shared/info.yaml");
    fs.writeFileSync(info, fs.readFileSync(info, "utf8").replace("{{conventions}}", "{{nowhere}}"));

    assert.throws(() => build(config, { version: "1.0.0" }), /no Markdown file found for \{\{nowhere\}\}/);
});

test("a bundle root that does not exist is reported against its target", { timeout: 300000 }, () => {
    const config = scaffold();
    fs.rmSync(path.join(config.sourceRoot(), "openapi/bundles/example-service_openapi_structure.yaml"));

    assert.throws(() => build(config, { version: "1.0.0" }), /bundle root not found/);
});
