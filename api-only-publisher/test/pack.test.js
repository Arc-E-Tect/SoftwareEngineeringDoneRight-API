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

// ------------------------------------------------------------------ reproducibility

const { execFileSync: run } = require("node:child_process");
const { pack } = require("../src/pack");

function withEpoch(value, body) {
    const saved = process.env.SOURCE_DATE_EPOCH;
    if (value === undefined) delete process.env.SOURCE_DATE_EPOCH; else process.env.SOURCE_DATE_EPOCH = value;
    try {
        return body();
    } finally {
        if (saved === undefined) delete process.env.SOURCE_DATE_EPOCH; else process.env.SOURCE_DATE_EPOCH = saved;
    }
}

test("packing the same documents twice produces byte-identical archives and manifests", () => {
    const config = library();
    document(config, "svc", "openapi.yaml", "openapi: 3.1.1\ninfo:\n  version: 1.0.0\n");
    document(config, "svc", "asyncapi.yaml", "asyncapi: 3.0.0\ninfo:\n  version: 1.0.0\n");

    const [first, second] = withEpoch("1767225600", () => [1, 2].map((n) => {
        const result = pack(config, "svc", { version: "1.0.0", closureSha256: "c", outDir: path.join(config.root, `out${n}`) });
        return { archive: fs.readFileSync(result.archive), manifest: fs.readFileSync(result.manifestPath) };
    }));

    assert.ok(first.archive.equals(second.archive), "archive bytes");
    assert.ok(first.manifest.equals(second.manifest), "manifest bytes");
});

test("the archive is a gzipped tar any tar reads, with the documents and manifest.json at its root", () => {
    const config = library();
    document(config, "svc", "openapi.yaml", "openapi: 3.1.1\ninfo:\n  version: 1.0.0\n");
    const { archive } = withEpoch("1767225600",
        () => pack(config, "svc", { version: "1.0.0", closureSha256: "c", outDir: path.join(config.root, "out") }));

    assert.deepStrictEqual(run("tar", ["-tzf", archive], { encoding: "utf8" }).trim().split("\n"),
        ["manifest.json", "openapi.yaml"]);
    const out = fs.mkdtempSync(path.join(os.tmpdir(), "aop-pack-x-"));
    run("tar", ["-xzf", archive, "-C", out]);
    assert.strictEqual(fs.readFileSync(path.join(out, "openapi.yaml"), "utf8"), "openapi: 3.1.1\ninfo:\n  version: 1.0.0\n");
});

test("producedAt is the time of the commit packed, and SOURCE_DATE_EPOCH overrides it", () => {
    const config = library();
    const git = (...args) => run("git", args, { cwd: config.root, encoding: "utf8",
        env: { ...process.env, GIT_AUTHOR_DATE: "2026-03-01T10:00:00Z", GIT_COMMITTER_DATE: "2026-03-01T10:00:00Z" } });
    git("init", "-q");
    git("-c", "user.email=t@example.com", "-c", "user.name=T", "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "c");
    const file = document(config, "svc", "openapi.yaml", "openapi: 3.1.1\n");

    assert.strictEqual(withEpoch(undefined, () => manifest(config, "svc", { version: "1.0.0", files: [file] })).producedAt,
        "2026-03-01T10:00:00Z");
    assert.strictEqual(withEpoch("1767225600", () => manifest(config, "svc", { version: "1.0.0", files: [file] })).producedAt,
        "2026-01-01T00:00:00Z");
});

test("a SOURCE_DATE_EPOCH that is not a whole number of seconds is refused", () => {
    const config = library();
    const file = document(config, "svc", "openapi.yaml", "openapi: 3.1.1\n");
    assert.throws(() => withEpoch("yesterday", () => manifest(config, "svc", { version: "1.0.0", files: [file] })),
        (e) => e instanceof PackError && /SOURCE_DATE_EPOCH/.test(e.message));
});
