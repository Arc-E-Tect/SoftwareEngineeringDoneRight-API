"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { load } = require("../src/config");
const { prepare } = require("../src/pipeline");
const { unreferenced } = require("../src/unreferenced");

const CONFIG = `schemaVersion: 1
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
targets:
  svc:
    openapi:
      bundle: bundles/svc.yaml
    asyncapi:
      bundle: svc.yaml
`;

// A library whose trees reference each other, as a real one does.
const FILES = {
    "apionly.yaml": CONFIG,
    "specs/openapi/bundles/svc.yaml": "paths:\n  /a:\n    $ref: '../paths/A.yaml'\n",
    "specs/openapi/paths/A.yaml": "get: {}\n",
    "specs/openapi/components/Shared.yml": "type: string\n",
    "specs/asyncapi/svc.yaml": "channels:\n  c:\n    $ref: 'messages/M.yaml'\n",
    "specs/asyncapi/messages/M.yaml": "payload:\n  $ref: '../../openapi/components/Shared.yml'\n",
};

function library(files) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-unreferenced-"));
    for (const [rel, content] of Object.entries(files)) {
        const file = path.join(dir, rel);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, content);
    }
    const config = load(path.join(dir, "apionly.yaml"));
    prepare(config);
    return config;
}

test("every fragment some target reaches is referenced, across the two trees", () => {
    assert.deepStrictEqual(unreferenced(library(FILES)), []);
});

test("a fragment no target reaches is unreferenced, named relative to the source root", () => {
    const config = library({
        ...FILES,
        "specs/openapi/paths/Unused.yaml": "get: {}\n",
        "specs/asyncapi/messages/Unused.yml": "payload: {}\n",
    });
    assert.deepStrictEqual(unreferenced(config), ["asyncapi/messages/Unused.yml", "openapi/paths/Unused.yaml"]);
});

test("Markdown snippets and version files are not fragments", () => {
    const config = library({
        ...FILES,
        "specs/openapi/shared/intro.md": "Prose pulled in by a placeholder.\n",
        "specs/openapi/bundles/svc.bundle.properties": "version=1.0.0\n",
    });
    assert.deepStrictEqual(unreferenced(config), []);
});
