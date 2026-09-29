"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { writeHandoff, HANDOFF_JSON, HANDOFF_SHA256, HANDOFF_SCHEMA_VERSION } = require("../src/handoff");

test("writeHandoff lists every file with its hash, target, version and channel, relative to the root", () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "aop-handoff-"));
    const outDir = path.join(root, "build", "packages");
    fs.mkdirSync(path.join(root, "out", "b"), { recursive: true });
    fs.writeFileSync(path.join(root, "out", "b", "two.tgz"), "two");
    fs.writeFileSync(path.join(root, "out", "one.tgz"), "one");

    const written = writeHandoff(root, outDir, [
        { file: path.join(root, "out", "one.tgz"), target: "alpha", version: "1.0.0", channel: "file" },
        { file: path.join(root, "out", "b", "two.tgz"), target: "gamma", version: "2.0.0", channel: "maven" },
    ]);

    assert.deepStrictEqual(written, { json: path.join(outDir, HANDOFF_JSON), sha256: path.join(outDir, HANDOFF_SHA256) });
    const listed = JSON.parse(fs.readFileSync(written.json, "utf8"));
    assert.strictEqual(listed.schemaVersion, HANDOFF_SCHEMA_VERSION);
    assert.deepStrictEqual(listed.files, [
        {
            path: "out/b/two.tgz", sha256: "3fc4ccfe745870e2c0d99f71f30ff0656c8dedd41cc1d7d3d376b0dbe685e2f3",
            target: "gamma", version: "2.0.0", channel: "maven",
        },
        {
            path: "out/one.tgz", sha256: "7692c3ad3540bb803c020b3aee66cd8887123234ea0c6e7143c0add73ff431ed",
            target: "alpha", version: "1.0.0", channel: "file",
        },
    ]);
    assert.strictEqual(fs.readFileSync(written.sha256, "utf8"),
        "3fc4ccfe745870e2c0d99f71f30ff0656c8dedd41cc1d7d3d376b0dbe685e2f3  out/b/two.tgz\n" +
        "7692c3ad3540bb803c020b3aee66cd8887123234ea0c6e7143c0add73ff431ed  out/one.tgz\n");
});

test("a run that wrote nothing locally still replaces the hand-off, with an empty list", () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "aop-handoff-"));
    const outDir = path.join(root, "build", "packages");
    fs.mkdirSync(outDir, { recursive: true });
    fs.writeFileSync(path.join(outDir, HANDOFF_SHA256), "stale\n");
    writeHandoff(root, outDir, []);
    assert.deepStrictEqual(JSON.parse(fs.readFileSync(path.join(outDir, HANDOFF_JSON), "utf8")).files, []);
    assert.strictEqual(fs.readFileSync(path.join(outDir, HANDOFF_SHA256), "utf8"), "");
});

test("the hand-off always uses forward slashes, whatever the platform", () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "aop-handoff-"));
    fs.mkdirSync(path.join(root, "a", "b"), { recursive: true });
    fs.writeFileSync(path.join(root, "a", "b", "c.tgz"), "c");
    writeHandoff(root, root, [{ file: path.join(root, "a", "b", "c.tgz"), target: "t", version: "1.0.0", channel: "file" }]);
    assert.match(fs.readFileSync(path.join(root, HANDOFF_SHA256), "utf8"), / {2}a\/b\/c\.tgz\n$/);
});

// The sha256sum on PATH is what a publishing job runs; skip where there is none.
const sha256sum = (() => {
    try {
        execFileSync("sha256sum", ["--version"], { stdio: "ignore" });
        return true;
    } catch {
        return false;
    }
})();

test("sha256sum -c accepts the hand-off from the root, and rejects a changed file", { skip: !sha256sum && "no sha256sum" }, () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "aop-handoff-"));
    fs.writeFileSync(path.join(root, "one.tgz"), "one");
    const outDir = path.join(root, "build", "packages");
    writeHandoff(root, outDir, [{ file: path.join(root, "one.tgz"), target: "t", version: "1.0.0", channel: "file" }]);
    execFileSync("sha256sum", ["-c", path.join("build", "packages", HANDOFF_SHA256)], { cwd: root, stdio: "pipe" });
    fs.writeFileSync(path.join(root, "one.tgz"), "changed");
    assert.throws(() => execFileSync("sha256sum", ["-c", path.join("build", "packages", HANDOFF_SHA256)],
        { cwd: root, stdio: "pipe" }));
});
