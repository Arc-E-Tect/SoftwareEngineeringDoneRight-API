"use strict";

// `check`, and what `publish` does before and while it ships: an invalid target -- one
// changed under a version released with other specifications -- fails the run before
// anything is packed; a channel that cannot take the version is a warning for that
// channel, and never fails the run on its own, since a channel is an external party.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { main } = require("../src/cli");
const { loadFrom } = require("../src/config");
const { prepare } = require("../src/pipeline");
const { HANDOFF_JSON } = require("../src/handoff");

const CONFIG = (channels) => `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
defaults:
  openapi:
    outputName: openapi.yaml
build:
  staging: build/staging
  dist: dist
channels:
${channels}targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
  beta:
    publish: false
    openapi:
      bundle: bundles/beta.yaml
`;

const FILE_CHANNEL = "  file:\n    directory: build/publish\n";

const FILES = {
    ".gitignore": "build/\ndist/\n",
    "specs/openapi/bundles/alpha.yaml": "openapi: 3.1.1\ninfo:\n  title: Alpha\n  version: 0.0.0\npaths:\n  /a:\n    $ref: '../paths/A.yaml'\n",
    "specs/openapi/bundles/alpha.bundle.properties": "version=1.0.0\n",
    "specs/openapi/bundles/beta.yaml": "openapi: 3.1.1\ninfo:\n  title: Beta\n  version: 0.0.0\npaths: {}\n",
    "specs/openapi/paths/A.yaml": "get:\n  x: {}\n",
};

function write(dir, rel, content) {
    fs.mkdirSync(path.dirname(path.join(dir, rel)), { recursive: true });
    fs.writeFileSync(path.join(dir, rel), content);
}

function git(dir, ...args) {
    return execFileSync("git", args, { cwd: dir, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

/** A library; in git, on branch `topic` off `main`, unless `inGit` is false. */
function library({ channels = FILE_CHANNEL, inGit = true } = {}) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-pubguard-"));
    write(dir, "apionly.yaml", CONFIG(channels));
    for (const [rel, content] of Object.entries(FILES)) write(dir, rel, content);
    if (inGit) {
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.email", "test@example.invalid");
        git(dir, "config", "user.name", "Test");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "add", "-A");
        git(dir, "commit", "-qm", "initial");
        git(dir, "checkout", "-qb", "topic");
    }
    prepare(loadFrom(dir));
    return dir;
}

/** alpha as build leaves it: its document in dist/, stamped with the version; `extra` changes its content. */
function built(dir, version, extra = "") {
    write(dir, "dist/alpha/openapi.yaml", `openapi: 3.1.1\ninfo:\n  title: X\n  version: ${version}\npaths: {}\n${extra}`);
}

function change(dir) {
    write(dir, "specs/openapi/paths/A.yaml", "get:\n  x: {}\n  y: {}\n");
}

async function run(argv, env = {}) {
    const printed = [];
    const saved = Object.fromEntries(Object.keys(env).map((k) => [k, process.env[k]]));
    Object.assign(process.env, env);
    const previousLog = console.log;
    console.log = (message) => printed.push(String(message));
    try {
        return { code: await main(argv), printed, text: printed.join("\n") };
    } finally {
        console.log = previousLog;
        for (const [k, v] of Object.entries(saved)) if (v === undefined) delete process.env[k]; else process.env[k] = v;
    }
}

function handoff(dir) {
    return JSON.parse(fs.readFileSync(path.join(dir, "build", "packages", HANDOFF_JSON), "utf8"));
}

// ------------------------------------------------------------------ check

test("check passes a target whose change comes with a new version, and fails one without, naming it", async () => {
    const dir = library();
    const { code, text } = await run(["check", "-C", dir]);
    assert.strictEqual(code, 0);
    assert.match(text, /^alpha +1\.0\.0 +ok +unchanged since the merge-base with main/m);
    assert.doesNotMatch(text, /beta/, "a target that is not published has no version to check");

    change(dir);
    await assert.rejects(main(["check", "-q", "-C", dir]),
        /1 target\(s\) changed without a new version:\n {2}alpha 1\.0\.0: alpha changed since the merge-base with main/);

    write(dir, "specs/openapi/bundles/alpha.bundle.properties", "version=1.1.0\n");
    assert.strictEqual((await run(["check", "-C", dir])).code, 0);
});

test("check takes --pre-release, --since and --target", async () => {
    const dir = library();
    change(dir);
    assert.strictEqual((await run(["check", "--pre-release", "rc.1", "-C", dir])).code, 0);
    git(dir, "add", "-A");
    git(dir, "commit", "-qm", "change");
    assert.strictEqual((await run(["check", "--since", "HEAD", "-C", dir])).code, 0, "nothing changed since HEAD");
    await assert.rejects(main(["check", "--target", "beta", "-C", dir]), /beta is not published, so it has no version to check/);
});

test("check fails on a target it cannot decide, and publish warns about it and publishes", async () => {
    const dir = library();
    git(dir, "branch", "-q", "-m", "main", "trunk"); // no main branch to compare with
    await assert.rejects(main(["check", "-q", "-C", dir]),
        /1 target\(s\) could not be checked:\n {2}alpha 1\.0\.0: no merge-base with main/);
    built(dir, "1.0.0");
    const { code, text } = await run(["publish", "-C", dir]);
    assert.strictEqual(code, 0);
    assert.match(text, /^warning: alpha 1\.0\.0: version not checked: no merge-base with main/m);
    assert.ok(fs.existsSync(path.join(dir, "build", "publish", "alpha", "1.0.0", "alpha-1.0.0.tgz")));
});

test("check outside a git repository fails: it cannot decide", async () => {
    const dir = library({ inGit: false });
    await assert.rejects(main(["check", "-C", dir]), /not in a git repository/);
});

// ------------------------------------------------------------------ publish: the target

test("publish refuses a target changed without a new version before packing or sending anything", async () => {
    const dir = library();
    change(dir);
    built(dir, "1.0.0");
    await assert.rejects(main(["publish", "-q", "-C", dir]), /alpha 1\.0\.0: alpha changed since the merge-base/);
    assert.ok(!fs.existsSync(path.join(dir, "build", "packages")), "nothing packed");
    assert.ok(!fs.existsSync(path.join(dir, "build", "publish")), "nothing published");
});

test("publish outside a git repository publishes, and says the versions were not checked", async () => {
    const dir = library({ inGit: false });
    built(dir, "1.0.0");
    const { code, text } = await run(["publish", "-C", dir]);
    assert.strictEqual(code, 0);
    assert.match(text, /warning: versions not checked: .*not in a git repository/);
    assert.ok(fs.existsSync(path.join(dir, "build", "publish", "alpha", "1.0.0", "alpha-1.0.0.tgz")));
});

// ------------------------------------------------------------------ publish: the channels

test("the file channel leaves a version with the same documents alone, and does not replace one with others", async () => {
    const dir = library({ inGit: false });
    built(dir, "1.0.0");
    await run(["publish", "-C", dir]);
    const archive = path.join(dir, "build", "publish", "alpha", "1.0.0", "alpha-1.0.0.tgz");
    const before = fs.readFileSync(archive);

    let { code, text } = await run(["publish", "-C", dir]);
    assert.strictEqual(code, 0);
    assert.match(text, /already published, identical/);
    assert.deepStrictEqual(handoff(dir).outcomes.map((o) => o.outcome), ["already-published"]);

    built(dir, "1.0.0", "x-changed: true\n");
    ({ code, text } = await run(["publish", "-C", dir]));
    assert.strictEqual(code, 0, "a channel problem never fails the run");
    assert.match(text, /^warning: alpha 1\.0\.0 was not published to file: .*already has alpha 1\.0\.0, and it is not this one/m);
    assert.ok(fs.readFileSync(archive).equals(before), "the published version is not replaced");
    const [outcome] = handoff(dir).outcomes;
    assert.deepStrictEqual({ ...outcome, reason: undefined },
        { target: "alpha", version: "1.0.0", channel: "file", type: "file", outcome: "conflict", reason: undefined });
    assert.ok(handoff(dir).files.every((f) => !f.path.startsWith("build/publish/")), "a channel that did not publish lists no files");
});

test("the file channel's clean keeps a version that conflicts, and removes the others", async () => {
    const dir = library({ inGit: false, channels: "  file:\n    directory: build/publish\n    clean: true\n" });
    write(dir, "build/publish/alpha/0.9.0/manifest.json", "{}");
    built(dir, "1.0.0");
    await run(["publish", "-C", dir]);
    assert.ok(!fs.existsSync(path.join(dir, "build", "publish", "alpha", "0.9.0")), "clean removes the others");
    built(dir, "1.0.0", "x-changed: true\n");
    const { text } = await run(["publish", "-C", dir]);
    assert.match(text, /not this one/);
    assert.ok(fs.existsSync(path.join(dir, "build", "publish", "alpha", "1.0.0", "alpha-1.0.0.tgz")));
});

test("a channel that cannot be reached, or has no credential, is a warning; the other channels still publish", async () => {
    const channels = FILE_CHANNEL +
        "  partner:\n    type: maven\n    groupId: com.example\n    repository: http://127.0.0.1:9/repo\n    tokenEnv: PARTNER_TOKEN\n" +
        "  vendor:\n    type: maven\n    groupId: com.example\n    repository: http://127.0.0.1:9/repo\n    tokenEnv: VENDOR_TOKEN\n";
    const dir = library({ inGit: false, channels });
    built(dir, "1.0.0");
    const { code, text } = await run(["publish", "-C", dir], { PARTNER_TOKEN: "t", VENDOR_TOKEN: "" });
    assert.strictEqual(code, 0);
    assert.ok(fs.existsSync(path.join(dir, "build", "publish", "alpha", "1.0.0", "alpha-1.0.0.tgz")), "file still published");
    assert.match(text, /^warning: alpha 1\.0\.0 was not published to partner: /m);
    assert.match(text, /^warning: alpha 1\.0\.0 was not published to vendor: no credential in \$VENDOR_TOKEN/m);
    assert.match(text, /^Channel problems: 2 of 3 publication\(s\) did not complete\.$/m);
    assert.deepStrictEqual(handoff(dir).outcomes.map((o) => `${o.channel} ${o.outcome}`),
        ["file published", "partner unavailable", "vendor unavailable"]);
});

test("when every channel fails the run still succeeds, and says nothing was published", async () => {
    const dir = library({ inGit: false,
        channels: "  maven:\n    groupId: com.example\n    repository: http://127.0.0.1:9/repo\n    tokenEnv: NO_SUCH_TOKEN\n" });
    built(dir, "1.0.0");
    const { code, text } = await run(["publish", "-C", dir], { NO_SUCH_TOKEN: "" });
    assert.strictEqual(code, 0);
    assert.match(text, /^Channel problems: 1 of 1 publication\(s\) did not complete\. Nothing was published\.$/m);
});

test("in GitHub Actions a channel problem is also an annotation", async () => {
    const dir = library({ inGit: false,
        channels: "  maven:\n    groupId: com.example\n    repository: http://127.0.0.1:9/repo\n    tokenEnv: NO_SUCH_TOKEN\n" });
    built(dir, "1.0.0");
    const { text } = await run(["publish", "-q", "-C", dir], { NO_SUCH_TOKEN: "", GITHUB_ACTIONS: "true" });
    assert.match(text, /^::warning title=alpha 1\.0\.0 not published to maven::no credential in \$NO_SUCH_TOKEN/m);
});

test("a channel's own settings that are wrong still fail the run: that is the library's to fix", async () => {
    const dir = library({ inGit: false, channels: "  maven:\n    repository: http://127.0.0.1:9/repo\n" });
    built(dir, "1.0.0");
    await assert.rejects(main(["publish", "-q", "-C", dir]), /the maven channel requires a groupId/);
});

test("outcomeOf tells a channel problem from the library's own error", () => {
    const { outcomeOf, ChannelError, ChannelConflict, ChannelUnavailable } = require("../src/channel-error");
    assert.strictEqual(outcomeOf(new ChannelConflict("x")), "conflict");
    assert.strictEqual(outcomeOf(new ChannelUnavailable("x")), "unavailable");
    assert.strictEqual(outcomeOf(new TypeError("fetch failed")), "unavailable", "no answer at all");
    let exited;
    try {
        execFileSync(process.execPath, ["-e", "process.exit(3)"], { stdio: "pipe" });
    } catch (error) {
        exited = error;
    }
    assert.strictEqual(outcomeOf(exited), "unavailable", "a registry client that exited with an error");
    assert.strictEqual(outcomeOf(new ChannelError("x")), null, "a setting the library has to fix");
    assert.strictEqual(outcomeOf(new TypeError("x is not a function")), null, "a bug is never a channel problem");
});
