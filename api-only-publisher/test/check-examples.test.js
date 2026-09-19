"use strict";

// checkExamples wired into the pipeline: config governs its severity, and it is
// reported apart from lint's own findings. The pure per-kind logic itself is
// tested directly in examples.test.js; this covers only the warn/error/off glue.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { load } = require("../src/config");
const { checkExamples, BuildError } = require("../src/pipeline");

const MINIMAL = `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
  asyncapi: asyncapi
defaults:
  openapi:
    outputName: openapi.yaml
  asyncapi:
    outputName: asyncapi.yaml
build:
  staging: build/staging
  dist: dist
toolchain:
  redocly: "@redocly/cli@2.52.0"
  asyncapi: "@asyncapi/cli@6.0.2"
targets:
  svc:
    openapi:
      bundle: bundles/svc.yaml
    asyncapi:
      bundle: svc.yaml
`;

const WITHOUT_EXAMPLE = `asyncapi: 3.0.0
info: {title: T, version: "1.0.0"}
channels:
  audit:
    address: audit.v1
    messages:
      m: {payload: {type: string}}
operations:
  publishAudit:
    action: send
    channel: {$ref: '#/channels/audit'}
`;

function project(lintYaml) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-check-examples-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"), MINIMAL + (lintYaml || ""));
    const bundle = path.join(dir, "bundle.yaml");
    fs.writeFileSync(bundle, WITHOUT_EXAMPLE);
    return { config: load(path.join(dir, "apionly.yaml")), bundle };
}

test("warn, the default, logs each finding and does not fail the build", () => {
    const { config, bundle } = project();
    const lines = [];

    checkExamples(config, "asyncapi", bundle, (line) => lines.push(line));

    assert.strictEqual(lines.length, 1);
    assert.match(lines[0], /publishAudit/);
    assert.match(lines[0], /cannot be used for Microcks-based conformance testing/);
});

test("error logs the same findings, then fails the build naming the count and the kind", () => {
    const { config, bundle } = project("lint:\n  examples:\n    asyncapi: error\n");
    const lines = [];

    assert.throws(
        () => checkExamples(config, "asyncapi", bundle, (line) => lines.push(line)),
        (e) => e instanceof BuildError && /1 operation\(s\)/.test(e.message)
            && /lint\.examples\.asyncapi is error/.test(e.message));
    assert.strictEqual(lines.length, 1, "the finding is still logged before the build fails");
});

test("off looks at nothing: no log line, no error", () => {
    const { config, bundle } = project("lint:\n  examples:\n    asyncapi: off\n");
    const lines = [];

    checkExamples(config, "asyncapi", bundle, (line) => lines.push(line));

    assert.deepStrictEqual(lines, []);
});

test("a document with every example present logs nothing, whatever the mode", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-check-examples-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"), MINIMAL + "lint:\n  examples:\n    asyncapi: error\n");
    const bundle = path.join(dir, "bundle.yaml");
    fs.writeFileSync(bundle, `asyncapi: 3.0.0
info: {title: T, version: "1.0.0"}
channels:
  audit:
    address: audit.v1
    messages:
      m:
        payload: {type: string}
        examples:
          - {name: E, payload: "x"}
operations:
  publishAudit:
    action: send
    channel: {$ref: '#/channels/audit'}
`);
    const lines = [];

    checkExamples(load(path.join(dir, "apionly.yaml")), "asyncapi", bundle, (line) => lines.push(line));

    assert.deepStrictEqual(lines, []);
});
