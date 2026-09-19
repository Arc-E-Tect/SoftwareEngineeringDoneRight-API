"use strict";

const test = require("node:test");
const assert = require("node:assert");
const YAML = require("yaml");

const { addMissingConfig } = require("../src/init-config");
const { scaffold, DEFAULTS } = require("../src/init");

const OPENAPI = { ...DEFAULTS, kinds: ["openapi"] };
const ASYNCAPI = { ...DEFAULTS, kinds: ["asyncapi"] };
const BOTH = { ...DEFAULTS, kinds: ["openapi", "asyncapi"] };

test("adds a missing kind's slots to an apionly.yaml that already builds the other, and nothing else", () => {
    const before = scaffold(OPENAPI)["apionly.yaml"];
    const { text, added } = addMissingConfig(before, BOTH);

    assert.deepStrictEqual(added,
        ["sources.asyncapi", "defaults.asyncapi", "toolchain.asyncapi", "targets.example-service.asyncapi"]);

    const doc = YAML.parse(text);
    assert.strictEqual(doc.sources.asyncapi, "asyncapi");
    assert.deepStrictEqual(doc.defaults.asyncapi, { outputName: "asyncapi.yaml" });
    assert.strictEqual(doc.toolchain.asyncapi, "@asyncapi/cli@6.0.2");
    assert.deepStrictEqual(doc.targets["example-service"].asyncapi,
        { bundle: "bundles/example-service_asyncapi_structure.yaml" });

    // Every openapi value the file already had is untouched.
    assert.strictEqual(doc.sources.openapi, "openapi");
    assert.deepStrictEqual(doc.defaults.openapi, { lint: ".redocly.yaml", outputName: "openapi.yaml" });
    assert.strictEqual(doc.toolchain.redocly, "@redocly/cli@2.52.0");
    assert.deepStrictEqual(doc.targets["example-service"].openapi,
        { bundle: "bundles/example-service_openapi_structure.yaml" });

    // The comments a hand-authored file relies on survive.
    assert.match(text, /# apionly\.yaml/);
    assert.match(text, /# Declares what this library builds, from where, and where each document goes\./);
});

test("adding the one missing kind lands each slot where a from-scratch scaffold would put it", () => {
    // Not just correct -- in the same relative order config() itself writes, so a plain
    // kind addition to an unmodified file ends up byte-identical to a fresh BOTH scaffold,
    // and a later init with the same values finds nothing left to add.
    const openapiOnly = scaffold(OPENAPI)["apionly.yaml"];
    const { text: addedAsync } = addMissingConfig(openapiOnly, BOTH);
    assert.strictEqual(addedAsync, scaffold(BOTH)["apionly.yaml"]);

    const asyncapiOnly = scaffold(ASYNCAPI)["apionly.yaml"];
    const { text: addedOpen } = addMissingConfig(asyncapiOnly, BOTH);
    assert.strictEqual(addedOpen, scaffold(BOTH)["apionly.yaml"]);
});

test("adds nothing, and returns the very same text, when every requested kind is already declared", () => {
    const before = scaffold(OPENAPI)["apionly.yaml"];
    const { text, added } = addMissingConfig(before, OPENAPI);

    assert.deepStrictEqual(added, []);
    assert.strictEqual(text, before);
});

test("leaves a customised defaults block alone -- a missing key inside one is a choice, not an absence", () => {
    // defaults.openapi with no `lint`: someone turned linting off for this kind on purpose.
    const before = scaffold(OPENAPI)["apionly.yaml"].replace("    lint: .redocly.yaml\n", "");
    assert.doesNotMatch(before, /lint:/);

    const { text, added } = addMissingConfig(before, BOTH);

    assert.ok(added.includes("defaults.asyncapi"));
    assert.ok(!added.includes("defaults.openapi"), "defaults.openapi already exists as a key; it is not touched");
    const doc = YAML.parse(text);
    assert.deepStrictEqual(doc.defaults.openapi, { outputName: "openapi.yaml" });
});

test("adds a whole new target to a multi-target library, and touches no other target", () => {
    const before = `# apionly.yaml
schemaVersion: 1

sources:
  root: specs
  openapi: openapi

defaults:
  openapi:
    lint: .redocly.yaml
    outputName: openapi.yaml
  placeholders:
    strict: true

build:
  staging: build/staging
  dist: dist

toolchain:
  redocly: "@redocly/cli@2.52.0"

targets:
  alpha:
    openapi:
      bundle: bundles/alpha_openapi_structure.yaml
  beta:
    openapi:
      bundle: bundles/beta_openapi_structure.yaml
    publish: false
`;
    const values = { ...OPENAPI, target: "gamma" };
    const { text, added } = addMissingConfig(before, values);

    assert.deepStrictEqual(added, ["targets.gamma.openapi"]);
    const doc = YAML.parse(text);
    assert.deepStrictEqual(doc.targets.gamma.openapi, { bundle: "bundles/gamma_openapi_structure.yaml" });
    // The existing targets are exactly as they were.
    assert.deepStrictEqual(doc.targets.alpha, { openapi: { bundle: "bundles/alpha_openapi_structure.yaml" } });
    assert.deepStrictEqual(doc.targets.beta,
        { openapi: { bundle: "bundles/beta_openapi_structure.yaml" }, publish: false });
});

test("a target that already has both kinds, in a library that already declares both, gets nothing added", () => {
    const before = scaffold(BOTH)["apionly.yaml"];
    const { text, added } = addMissingConfig(before, BOTH);

    assert.deepStrictEqual(added, []);
    assert.strictEqual(text, before);
});

test("only the slots for the kinds asked for are ever considered", () => {
    // A both-kind file, asked only about openapi: nothing to add, whatever asyncapi holds.
    const before = scaffold(BOTH)["apionly.yaml"];
    const { added } = addMissingConfig(before, OPENAPI);
    assert.deepStrictEqual(added, []);
});

test("a file with no sources.root is not an apionly.yaml this can complete -- nothing is added to it", () => {
    const before = "# edited by hand\n";
    const { text, added } = addMissingConfig(before, BOTH);
    assert.deepStrictEqual(added, []);
    assert.strictEqual(text, before);
});

test("a file with a YAML error is left alone -- parseDocument does not throw on one, it only records it", () => {
    // sources.root parses fine before the error, so a check that only asked hasIn
    // would find it anyway; addMissingConfig must refuse instead of trying to edit
    // a document that cannot be stringified back.
    const before = `${scaffold(OPENAPI)["apionly.yaml"]}\n: : : not valid yaml [[[\n`;
    const { text, added } = addMissingConfig(before, BOTH);
    assert.deepStrictEqual(added, []);
    assert.strictEqual(text, before);
});

test("adding a new target to a library that already declares its kind's top-level slots adds only the target entry", () => {
    const before = scaffold(ASYNCAPI)["apionly.yaml"];
    const values = { ...ASYNCAPI, target: "second" };
    const { added } = addMissingConfig(before, values);

    // sources.asyncapi, defaults.asyncapi and toolchain.asyncapi are already there.
    assert.deepStrictEqual(added, ["targets.second.asyncapi"]);
});

// ---- the portfolio section, given only when the caller asks for it ----------

test("no portfolio argument means the portfolio section is never considered, whatever the file already holds", () => {
    const before = scaffold(OPENAPI)["apionly.yaml"];
    const { added } = addMissingConfig(before, OPENAPI);
    assert.ok(!added.includes("portfolio"));
});

test("a portfolio argument adds the section, with every strategy the caller resolved", () => {
    const before = scaffold(OPENAPI)["apionly.yaml"];
    const { text, added } = addMissingConfig(before, OPENAPI, { paths: "none", location: "aggregates" });

    assert.ok(added.includes("portfolio"));
    const doc = YAML.parse(text);
    assert.deepStrictEqual(doc.portfolio, {
        openapi: { paths: "none", operationIds: "target-prefix", tags: "reconcile" },
        security: "push-down",
        location: "aggregates",
    });
});

test("a portfolio section that already exists is left entirely alone, whatever it holds", () => {
    const before = scaffold(OPENAPI)["apionly.yaml"] + "portfolio:\n  location: mine\n";
    const { text, added } = addMissingConfig(before, OPENAPI, { paths: "none", location: "aggregates" });

    assert.ok(!added.includes("portfolio"));
    assert.strictEqual(text, before);
});

test("the portfolio section lands after targets, the last section a from-scratch scaffold writes", () => {
    const before = scaffold(OPENAPI)["apionly.yaml"];
    const { text } = addMissingConfig(before, OPENAPI, { paths: "target-prefix", location: "portfolios" });
    assert.ok(text.indexOf("targets:") < text.indexOf("portfolio:"));
});
