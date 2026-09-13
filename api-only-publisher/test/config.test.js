"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { load, loadFrom, ConfigError } = require("../src/config");

const MINIMAL = `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
defaults:
  openapi:
    outputName: openapi.yaml
build:
  staging: build/staging
  dist: dist
toolchain:
  redocly: "@redocly/cli@2.52.0"
targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
  beta:
    publish: false
    openapi:
      bundle: bundles/beta.yaml
`;

function write(contents) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-config-"));
    const file = path.join(dir, "apionly.yaml");
    fs.writeFileSync(file, contents);
    return file;
}

test("reads targets in declaration order", () => {
    const config = load(write(MINIMAL));
    assert.deepStrictEqual(Object.keys(config.targets), ["alpha", "beta"]);
    assert.deepStrictEqual(config.targetsFor("openapi"), ["alpha", "beta"]);
});

test("publish: false marks a target as not distributed", () => {
    const config = load(write(MINIMAL));
    assert.strictEqual(config.isPublished("alpha"), true);
    assert.strictEqual(config.isPublished("beta"), false);
});

test("derives staging, dist and bundle paths from one root", () => {
    const file = write(MINIMAL);
    const config = load(file);
    const root = path.dirname(file);
    assert.strictEqual(config.sourceRoot(), path.join(root, "specs"));
    assert.strictEqual(config.stagingRoot("openapi"), path.join(root, "build/staging/openapi"));
    assert.strictEqual(config.stagingDir("openapi"), path.join(root, "build/staging/openapi/openapi"));
    assert.strictEqual(config.distDir("alpha"), path.join(root, "dist/alpha"));
    assert.strictEqual(config.bundlePath("alpha", "openapi"),
        path.join(root, "build/staging/openapi/openapi/bundles/alpha.yaml"));
});

test("an unsupported schemaVersion is refused by name", () => {
    assert.throws(() => load(write(MINIMAL.replace("schemaVersion: 1", "schemaVersion: 2"))),
        (e) => e instanceof ConfigError && /schemaVersion 2/.test(e.message));
});

test("a config with no targets is refused", () => {
    const noTargets = MINIMAL.slice(0, MINIMAL.indexOf("targets:")) + "targets: {}\n";
    assert.throws(() => load(write(noTargets)), (e) => e instanceof ConfigError && /no targets/.test(e.message));
});

test("a target declaring neither specification type is refused", () => {
    const bad = MINIMAL + "  gamma:\n    publish: true\n";
    assert.throws(() => load(write(bad)),
        (e) => e instanceof ConfigError && /neither an openapi nor an asyncapi/.test(e.message));
});

test("a target with no bundle path is refused", () => {
    const bad = MINIMAL + "  gamma:\n    openapi:\n      lint: x.yaml\n";
    assert.throws(() => load(write(bad)),
        (e) => e instanceof ConfigError && /targets\.gamma\.openapi\.bundle/.test(e.message));
});

test("the config is found by walking up from a subdirectory", () => {
    const file = write(MINIMAL);
    const deep = path.join(path.dirname(file), "specs", "openapi", "bundles");
    fs.mkdirSync(deep, { recursive: true });
    assert.strictEqual(loadFrom(deep).path, file);
});

test("a directory with no config reports where it looked", () => {
    const empty = fs.mkdtempSync(path.join(os.tmpdir(), "aop-empty-"));
    assert.throws(() => loadFrom(empty), (e) => e instanceof ConfigError && /no apionly\.yaml found/.test(e.message));
});

test("the transitional distribution block resolves {target}", () => {
    const withDist = MINIMAL + `
distribution:
  root: ../consumers
  layout: "{target}/src/main/resources"
`;
    const file = write(withDist);
    const config = load(file);
    assert.strictEqual(
        config.destinationDir("alpha"),
        path.resolve(path.dirname(file), "../consumers/alpha/src/main/resources")
    );
});

test("no distribution block means nothing is copied anywhere", () => {
    assert.strictEqual(load(write(MINIMAL)).destinationDir("alpha"), null);
});

test("a target's version file sits beside its first bundle root, unless the target names its own", () => {
    const file = write(MINIMAL.replace(
        "  beta:\n    publish: false\n",
        "  beta:\n    publish: false\n    versionFile: versions/beta.properties\n"));
    const config = load(file);
    const root = path.dirname(file);

    assert.strictEqual(config.versionFile("alpha"), path.join(root, "specs/openapi/bundles/alpha.bundle.properties"));
    assert.strictEqual(config.versionFile("beta"), path.join(root, "versions/beta.properties"));
});

test("an AsyncAPI-only target's version file sits beside its AsyncAPI bundle root", () => {
    const file = write(`schemaVersion: 1
sources:
  root: specs
  asyncapi: asyncapi
targets:
  events:
    asyncapi:
      bundle: events.yaml
`);
    assert.strictEqual(load(file).versionFile("events"), path.join(path.dirname(file), "specs/asyncapi/events.bundle.properties"));
});
