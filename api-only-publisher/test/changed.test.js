"use strict";

// `changed` is what stops one service's edit from releasing every other service,
// so it is worth testing against a real git history rather than a stub.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { load } = require("../src/config");
const { prepare } = require("../src/pipeline");
const { changedSince, ChangedError } = require("../src/changed");

const CONFIG = `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
defaults:
  openapi:
    outputName: openapi.yaml
build:
  staging: build/staging
targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
  beta:
    openapi:
      bundle: bundles/beta.yaml
`;

const FILES = {
    "apionly.yaml": CONFIG,
    ".gitignore": "build/\n",
    "specs/openapi/bundles/alpha.yaml":
        "openapi: 3.1.1\npaths:\n  /a:\n    $ref: '../paths/A.yaml'\n",
    "specs/openapi/bundles/beta.yaml":
        "openapi: 3.1.1\npaths:\n  /b:\n    $ref: '../paths/B.yaml'\n",
    "specs/openapi/paths/A.yaml": "get:\n  x:\n    $ref: '../components/common/Shared.yaml'\n",
    "specs/openapi/paths/B.yaml": "get:\n  y: {}\n",
    "specs/openapi/components/common/Shared.yaml": "type: string\n",
};

function git(dir, ...args) {
    return execFileSync("git", args, { cwd: dir, encoding: "utf8" });
}

function repository() {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-changed-"));
    for (const [rel, content] of Object.entries(FILES)) {
        const file = path.join(dir, rel);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, content);
    }
    git(dir, "init", "-q", "-b", "main");
    git(dir, "config", "user.email", "test@example.invalid");
    git(dir, "config", "user.name", "Test");
    git(dir, "config", "commit.gpgsign", "false");
    git(dir, "add", "-A");
    git(dir, "commit", "-qm", "initial");
    const config = load(path.join(dir, "apionly.yaml"));
    prepare(config, { kinds: ["openapi"] });
    return { dir, config };
}

function report(config, since) {
    return new Map(changedSince(config, since, { kinds: ["openapi"] }).map((r) => [r.target, r]));
}

test("reports nothing when nothing has changed", () => {
    const { config } = repository();

    const results = report(config, "HEAD");

    assert.strictEqual(results.get("alpha").changed, false);
    assert.strictEqual(results.get("beta").changed, false);
});

test("an edit to a shared fragment changes every target that reaches it", () => {
    const { dir, config } = repository();
    fs.writeFileSync(path.join(dir, "specs/openapi/components/common/Shared.yaml"), "type: integer\n");

    const results = report(config, "HEAD");

    assert.strictEqual(results.get("alpha").changed, true, "alpha reaches Shared.yaml");
    assert.strictEqual(results.get("beta").changed, false, "beta does not");
});

test("an edit inside one target's own context changes only that target", () => {
    const { dir, config } = repository();
    fs.writeFileSync(path.join(dir, "specs/openapi/paths/B.yaml"), "get:\n  y:\n    summary: changed\n");

    const results = report(config, "HEAD");

    assert.strictEqual(results.get("beta").changed, true);
    assert.strictEqual(results.get("alpha").changed, false);
});

test("names the files that changed, relative to the repository", () => {
    const { dir, config } = repository();
    fs.writeFileSync(path.join(dir, "specs/openapi/paths/B.yaml"), "get:\n  y:\n    summary: changed\n");

    assert.deepStrictEqual(report(config, "HEAD").get("beta").files, ["specs/openapi/paths/B.yaml"]);
});

test("a file outside every closure changes nothing", () => {
    const { dir, config } = repository();
    fs.writeFileSync(path.join(dir, "README.md"), "unrelated\n");

    const results = report(config, "HEAD");

    assert.strictEqual(results.get("alpha").changed, false);
    assert.strictEqual(results.get("beta").changed, false);
});

test("a bundle gaining a reference is caught, because the bundle itself changed", () => {
    // Closure membership is never diffed directly; changing the shape of a closure
    // always means editing a file already inside it.
    const { dir, config } = repository();
    fs.writeFileSync(path.join(dir, "specs/openapi/bundles/beta.yaml"),
        "openapi: 3.1.1\npaths:\n  /b:\n    $ref: '../paths/B.yaml'\n  /s:\n    $ref: '../components/common/Shared.yaml'\n");

    assert.strictEqual(report(config, "HEAD").get("beta").changed, true);
});

test("every report carries the closure hash that would be released", () => {
    const { config } = repository();

    for (const result of report(config, "HEAD").values()) {
        assert.match(result.closureSha256, /^[0-9a-f]{64}$/);
    }
});

test("an unknown ref fails with git's own complaint rather than silently reporting nothing", () => {
    const { config } = repository();

    assert.throws(() => report(config, "no-such-ref"), (error) => error instanceof ChangedError);
});
