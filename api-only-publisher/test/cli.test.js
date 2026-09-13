"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { main } = require("../src/cli");

test("lint writes the validator report when validation succeeds", async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"), `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
defaults:
  openapi:
    outputName: openapi.yaml
build:
  dist: dist
toolchain:
  redocly: "@redocly/cli@2.52.0"
targets:
  example:
    openapi:
      bundle: bundles/example.yaml
`);
    const output = path.join(dir, "dist", "example", "openapi.yaml");
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, "openapi: 3.1.1\ninfo:\n  title: Example\n  version: 1.0.0\npaths: {}\n");

    const bin = path.join(dir, "bin");
    fs.mkdirSync(bin);
    const npx = path.join(bin, "npx");
    fs.writeFileSync(npx, "#!/bin/sh\nprintf 'validator report: no errors\\n'\n");
    fs.chmodSync(npx, 0o755);

    const previousPath = process.env.PATH;
    const messages = [];
    const previousLog = console.log;
    process.env.PATH = `${bin}:${previousPath}`;
    console.log = (message) => messages.push(message);
    try {
        await main(["lint", "-C", dir]);
    } finally {
        process.env.PATH = previousPath;
        console.log = previousLog;
    }

    assert.ok(messages.includes("validator report: no errors"));
    assert.strictEqual(
        fs.readFileSync(path.join(dir, "build", "reports", "lint", "example", "openapi.txt"), "utf8"),
        "validator report: no errors\n"
    );
});

test("lint writes the validator report when validation fails", async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"), `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
defaults:
  openapi:
    outputName: openapi.yaml
build:
  dist: dist
toolchain:
  redocly: "@redocly/cli@2.52.0"
targets:
  example:
    openapi:
      bundle: bundles/example.yaml
`);
    const output = path.join(dir, "dist", "example", "openapi.yaml");
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, "openapi: 3.1.1\ninfo:\n  title: Example\n  version: 1.0.0\npaths: {}\n");

    const bin = path.join(dir, "bin");
    fs.mkdirSync(bin);
    const npx = path.join(bin, "npx");
    fs.writeFileSync(npx, "#!/bin/sh\nprintf 'validator report: errors found\\n' >&2\nexit 1\n");
    fs.chmodSync(npx, 0o755);

    const previousPath = process.env.PATH;
    process.env.PATH = `${bin}:${previousPath}`;
    try {
        await assert.rejects(() => main(["lint", "-C", dir]), /npx .* failed/);
    } finally {
        process.env.PATH = previousPath;
    }

    assert.strictEqual(
        fs.readFileSync(path.join(dir, "build", "reports", "lint", "example", "openapi.txt"), "utf8"),
        "validator report: errors found\n"
    );
});
