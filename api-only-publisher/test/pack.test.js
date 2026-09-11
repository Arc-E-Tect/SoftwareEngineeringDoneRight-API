"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { load } = require("../src/config");
const { manifest, MANIFEST_SCHEMA_VERSION, PackError } = require("../src/pack");
const { publish, ChannelError } = require("../src/channels");

function library() {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-pack-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"), `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
defaults:
  openapi:
    outputName: openapi.yaml
targets:
  svc:
    openapi:
      bundle: bundles/svc.yaml
`);
    return load(path.join(dir, "apionly.yaml"));
}

function document(config, target, name, content) {
    const dir = config.distDir(target);
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, name);
    fs.writeFileSync(file, content);
    return file;
}

test("the manifest carries the agreed fields", () => {
    const config = library();
    const file = document(config, "svc", "openapi.yaml", "openapi: 3.1.1\n");
    const data = manifest(config, "svc", { version: "2.1.0", files: [file], closureSha256: "abc123" });

    assert.strictEqual(data.schemaVersion, MANIFEST_SCHEMA_VERSION);
    assert.strictEqual(data.target, "svc");
    assert.strictEqual(data.version, "2.1.0");
    assert.strictEqual(data.closureSha256, "abc123");
    assert.match(data.producedAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
    assert.deepStrictEqual(Object.keys(data.source).sort(), ["commit", "repository"]);
    assert.strictEqual(data.files.length, 1);
    assert.strictEqual(data.files[0].path, "openapi.yaml");
    assert.match(data.files[0].sha256, /^[0-9a-f]{64}$/);
});

test("file entries are listed by name, sorted, without directory paths", () => {
    const config = library();
    const b = document(config, "svc", "openapi.yaml", "a\n");
    const a = document(config, "svc", "asyncapi.yaml", "b\n");
    const data = manifest(config, "svc", { version: "1.0.0", files: [b, a], closureSha256: "x" });
    assert.deepStrictEqual(data.files.map((f) => f.path), ["asyncapi.yaml", "openapi.yaml"]);
});

test("the file hash tracks content", () => {
    const config = library();
    const file = document(config, "svc", "openapi.yaml", "one\n");
    const before = manifest(config, "svc", { version: "1.0.0", files: [file], closureSha256: "x" }).files[0].sha256;
    fs.writeFileSync(file, "two\n");
    const after = manifest(config, "svc", { version: "1.0.0", files: [file], closureSha256: "x" }).files[0].sha256;
    assert.notStrictEqual(before, after);
});

test("packing without a version is refused", () => {
    const config = library();
    const file = document(config, "svc", "openapi.yaml", "x\n");
    assert.throws(() => manifest(config, "svc", { version: null, files: [file] }),
        (e) => e instanceof PackError);
});

test("an unknown channel names the ones that exist", () => {
    assert.throws(() => publish("/tmp/x.tgz", { target: "svc", version: "1.0.0" }, "smoke-signal", {}),
        (e) => e instanceof ChannelError && /file, maven/.test(e.message));
});

test("the maven channel requires a groupId", () => {
    assert.throws(() => publish("/tmp/x.tgz", { target: "svc", version: "1.0.0" }, "maven", {}),
        (e) => e instanceof ChannelError && /groupId/.test(e.message));
});

test("the maven channel writes a resolvable repository layout", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-m2-"));
    const archive = path.join(dir, "svc-1.0.0.tgz");
    fs.writeFileSync(archive, "archive bytes");
    const data = { target: "svc", version: "1.0.0", schemaVersion: 1 };

    const result = publish(archive, data, "maven", { groupId: "com.example.contracts", repository: path.join(dir, "m2") });

    const base = path.join(dir, "m2", "com", "example", "contracts", "svc", "1.0.0");
    assert.ok(fs.existsSync(path.join(base, "svc-1.0.0.tgz")));
    assert.ok(fs.existsSync(path.join(base, "svc-1.0.0.pom")));
    assert.match(fs.readFileSync(path.join(base, "svc-1.0.0.pom"), "utf8"), /<artifactId>svc<\/artifactId>/);
    assert.strictEqual(result.coordinates, "com.example.contracts:svc:1.0.0@tgz");
});
