"use strict";

// `check` and `changed` run from git hooks, and git runs a hook with GIT_DIR set. The
// Publisher finds the repository from the library's directory, so what GIT_DIR says must
// not change its answer (#125): absolute, as in a linked worktree, it made git take the
// library's directory for the top of the repository; relative, as in the main checkout,
// it named nothing from there.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { load } = require("../src/config");
const { prepare } = require("../src/pipeline");
const { changedSince } = require("../src/changed");
const { guard } = require("../src/version-guard");
const { useFakeBundler, REDOCLY_PIN } = require("./fake-bundler");

useFakeBundler();

const FILES = {
    ".gitignore": "build/\ndist/\n",
    "lib/apionly.yaml": `schemaVersion: 1
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
  redocly: "${REDOCLY_PIN}"
targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
`,
    "lib/specs/openapi/bundles/alpha.yaml": "openapi: 3.1.1\npaths:\n  /a:\n    $ref: '../paths/A.yaml'\n",
    "lib/specs/openapi/bundles/alpha.bundle.properties": "version=1.0.0\n",
    "lib/specs/openapi/paths/A.yaml": "get:\n  x: {}\n",
};

function git(dir, ...args) {
    const env = { ...process.env };
    delete env.GIT_DIR;
    return execFileSync("git", args, { cwd: dir, encoding: "utf8", env, stdio: ["ignore", "pipe", "pipe"] });
}

/** A repository whose library is below its top, released as alpha 1.0.0, on branch `topic`. */
function repository() {
    const dir = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "aop-gitenv-")));
    for (const [rel, content] of Object.entries(FILES)) {
        fs.mkdirSync(path.dirname(path.join(dir, rel)), { recursive: true });
        fs.writeFileSync(path.join(dir, rel), content);
    }
    git(dir, "init", "-q", "-b", "main");
    git(dir, "config", "user.email", "test@example.invalid");
    git(dir, "config", "user.name", "Test");
    git(dir, "config", "commit.gpgsign", "false");
    git(dir, "config", "tag.gpgsign", "false");
    git(dir, "add", "-A");
    git(dir, "commit", "-qm", "initial");
    git(dir, "tag", "alpha-v1.0.0");
    git(dir, "checkout", "-qb", "topic");
    return dir;
}

/** Run `action` with GIT_DIR set as a hook has it. */
function asInAHook(gitDir, action) {
    const saved = process.env.GIT_DIR;
    process.env.GIT_DIR = gitDir;
    try {
        return action();
    } finally {
        if (saved === undefined) delete process.env.GIT_DIR;
        else process.env.GIT_DIR = saved;
    }
}

for (const [where, gitDirOf] of [
    ["an absolute GIT_DIR, as in a linked worktree", (dir) => path.join(dir, ".git")],
    ["a relative GIT_DIR, as in the main checkout", () => ".git"],
]) {
    test(`changed, run with ${where}, compares the library's documents`, () => {
        const dir = repository();
        const config = load(path.join(dir, "lib", "apionly.yaml"));
        prepare(config, { kinds: ["openapi"] });
        const logged = [];
        const changed = () => asInAHook(gitDirOf(dir),
            () => changedSince(config, "HEAD", { kinds: ["openapi"], log: (line) => logged.push(line) })[0].changed);

        assert.strictEqual(changed(), false, logged.join("\n"));
        fs.writeFileSync(path.join(dir, "lib/specs/openapi/paths/A.yaml"), "get:\n  x: {}\n  y: {}\n");
        assert.strictEqual(changed(), true);
    });

    test(`check, run with ${where}, compares the library's documents`, () => {
        const dir = repository();
        const config = load(path.join(dir, "lib", "apionly.yaml"));
        const verdict = () => asInAHook(gitDirOf(dir), () => guard(config, ["alpha"])[0]);

        const unchanged = verdict();
        assert.strictEqual(unchanged.verdict, "ok", unchanged.reason);
        fs.writeFileSync(path.join(dir, "lib/specs/openapi/paths/A.yaml"), "get:\n  x: {}\n  y: {}\n");
        assert.strictEqual(verdict().verdict, "violation");
    });
}
