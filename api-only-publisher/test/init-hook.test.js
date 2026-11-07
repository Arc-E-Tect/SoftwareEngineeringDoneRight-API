"use strict";

// The pre-push hook `init` writes: a push of a target that changed without a new
// version never leaves the machine. README.adoc#version-hook.
//
// These tests push for real. Each library has a bare repository as its origin, and
// git runs the hook init wrote, exactly as it would for a developer. The hook calls
// npx or docker; a stand-in for each, first on PATH, runs this Publisher's own CLI
// (npx) or records how it was called (docker), so nothing is installed or pulled.
// The check builds each target's document, so the npx stand-in runs a stand-in
// bundler for the pinned @redocly/cli.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const { main } = require("../src/cli");
const { imageTag } = require("../src/init-hook");
const { fakeBundlerDir } = require("./fake-bundler");

// Nobody's own git configuration -- a global core.hooksPath, signing, a default
// branch -- may change what these tests see.
const EMPTY_GITCONFIG = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "aop-hook-cfg-")), "gitconfig");
fs.writeFileSync(EMPTY_GITCONFIG, "");
Object.assign(process.env, {
    GIT_CONFIG_GLOBAL: EMPTY_GITCONFIG, GIT_CONFIG_NOSYSTEM: "1",
    GIT_AUTHOR_NAME: "Test", GIT_AUTHOR_EMAIL: "test@example.invalid",
    GIT_COMMITTER_NAME: "Test", GIT_COMMITTER_EMAIL: "test@example.invalid",
});

const CLI = path.join(__dirname, "..", "src", "cli.js");
const VERSION_FILE = "specs/openapi/bundles/example-service.bundle.properties";
const FRAGMENT = "specs/openapi/paths/example/ExamplesV1.yaml";

/** A directory with stand-ins for npx and docker, put first on PATH for every push. */
const BIN = (() => {
    const bin = fs.mkdtempSync(path.join(os.tmpdir(), "aop-hook-bin-"));
    const bundler = path.join(fakeBundlerDir(), "node_modules", "@redocly", "cli", "bin", "cli.js");
    fs.writeFileSync(path.join(bin, "npx"),
        `#!/bin/sh\ncase "$2" in @redocly/cli@*) shift 2; exec "${bundler}" "$@";; esac\n` +
        `[ "$1" = --no-install ] && shift\nshift\nexec "${process.execPath}" "${CLI}" "$@"\n`, { mode: 0o755 });
    fs.writeFileSync(path.join(bin, "docker"),
        `#!/bin/sh\nprintf '%s\\n' "$PWD" "$@" > "${path.join(bin, "docker-called")}"\n`, { mode: 0o755 });
    return bin;
})();
const PATH_WITH_STANDINS = `${BIN}${path.delimiter}${process.env.PATH}`;

function tmpdir() {
    return fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "aop-hook-")));
}

function git(cwd, ...args) {
    const result = spawnSync("git", args, { cwd, encoding: "utf8", env: { ...process.env, PATH: PATH_WITH_STANDINS } });
    return { ok: result.status === 0, out: `${result.stdout}${result.stderr}` };
}

function mustGit(cwd, ...args) {
    const result = git(cwd, ...args);
    assert.ok(result.ok, `git ${args.join(" ")} failed:\n${result.out}`);
    return result.out.trim();
}

/** A clone of an empty bare origin: what a developer has after cloning a new repository. */
function clone() {
    const parent = tmpdir();
    mustGit(parent, "init", "-q", "--bare", "-b", "main", "origin.git");
    mustGit(parent, "clone", "-q", "origin.git", "work");
    return { top: path.join(parent, "work"), origin: path.join(parent, "origin.git") };
}

async function init(dir, ...flags) {
    const printed = [];
    const previous = console.log;
    console.log = (message) => printed.push(String(message));
    try {
        await main(["init", dir, "--yes", ...flags], { interactive: false });
        return printed;
    } finally {
        console.log = previous;
    }
}

function edit(dir, rel, from, to) {
    const file = path.join(dir, rel);
    const text = fs.readFileSync(file, "utf8");
    assert.ok(text.includes(from), `${rel} does not contain ${from}`);
    fs.writeFileSync(file, text.replace(from, to));
}

function commitAll(top, message) {
    mustGit(top, "add", "-A");
    mustGit(top, "commit", "-qm", message);
}

const pushed = (origin, branch) => git(origin, "rev-parse", "--verify", "--quiet", `refs/heads/${branch}`).ok;
const hooksPath = (top) => {
    const result = git(top, "config", "--get", "core.hooksPath");
    return result.ok ? result.out.trim() : null;
};

/** A library initialised at the top of a fresh clone, committed and pushed to main, then on branch topic. */
async function publishedLibrary(sub = "") {
    const { top, origin } = clone();
    const dir = path.join(top, sub);
    await init(dir);
    commitAll(top, "scaffold");
    mustGit(top, "push", "-q", "origin", "main");
    mustGit(top, "checkout", "-qb", "topic");
    return { top, origin, dir };
}

test("git push is refused when a published target changed without a new version, and nothing reaches origin", async () => {
    const { top, origin, dir } = await publishedLibrary();
    edit(dir, FRAGMENT, "List examples.", "List every example.");
    commitAll(top, "change the specification, not the version");

    const push = git(top, "push", "origin", "topic");

    assert.ok(!push.ok, "the push went through");
    assert.match(push.out, /example-service changed since the merge-base with main .*, but its version is still 0\.1\.0: raise it in specs\/openapi\/bundles\/example-service\.bundle\.properties/);
    assert.ok(!pushed(origin, "topic"), "the branch reached origin");
});

test("once the version is raised in the same change, the same push goes through", async () => {
    const { top, origin, dir } = await publishedLibrary();
    edit(dir, FRAGMENT, "List examples.", "List every example.");
    edit(dir, VERSION_FILE, "version=0.1.0", "version=0.1.1");
    commitAll(top, "change the specification and its version");

    const push = git(top, "push", "origin", "topic");

    assert.ok(push.ok, push.out);
    assert.match(push.out, /example-service +0\.1\.1 +ok/);
    assert.ok(pushed(origin, "topic"));
});

test("the hook fetches first: a version a teammate already released is refused, though this clone never fetched its tag", async () => {
    const { top, origin, dir } = await publishedLibrary();

    // A teammate releases 0.2.0 from main, and tags it, as the publishing pipeline would.
    const teammate = path.join(path.dirname(top), "teammate");
    mustGit(path.dirname(top), "clone", "-q", origin, teammate);
    edit(teammate, VERSION_FILE, "version=0.1.0", "version=0.2.0");
    edit(teammate, FRAGMENT, "List examples.", "List the examples.");
    commitAll(teammate, "release 0.2.0");
    mustGit(teammate, "tag", "example-service-v0.2.0");
    mustGit(teammate, "push", "-q", "origin", "main", "example-service-v0.2.0");

    // Here, unaware of it, another change claims 0.2.0 as well. Against what this clone
    // has, 0.1.0 -> 0.2.0 is a valid bump; only origin knows 0.2.0 is taken.
    edit(dir, FRAGMENT, "List examples.", "List every example.");
    edit(dir, VERSION_FILE, "version=0.1.0", "version=0.2.0");
    commitAll(top, "claim 0.2.0 too");

    const push = git(top, "push", "origin", "topic");

    assert.ok(!push.ok, "the push went through");
    assert.match(push.out, /0\.2\.0 was released as example-service-v0\.2\.0, and example-service has changed since/);
});

test("when origin cannot be reached, the hook says so and still checks against what this clone has", async () => {
    const { top, dir } = await publishedLibrary();
    mustGit(top, "remote", "set-url", "origin", path.join(path.dirname(top), "gone.git"));
    mustGit(top, "remote", "set-url", "--push", "origin", path.join(path.dirname(top), "origin.git"));
    edit(dir, FRAGMENT, "List examples.", "List every example.");
    commitAll(top, "change the specification, not the version");

    const push = git(top, "push", "origin", "topic");

    assert.ok(!push.ok, "the push went through");
    assert.match(push.out, /pre-push: could not fetch origin; checking against what is here/);
    assert.match(push.out, /but its version is still 0\.1\.0/);
});

test("init points this clone's git at the hook, and says every other clone needs the same one command", async () => {
    const { top } = clone();
    const printed = await init(top);

    assert.strictEqual(hooksPath(top), ".githooks");
    assert.ok(printed.includes("  created    .githooks/pre-push"));
    assert.ok(printed.includes("  configured core.hooksPath=.githooks (this clone runs the pre-push hook)"));
    assert.ok(printed.includes("Every other clone runs the pre-push hook after: git config core.hooksPath .githooks"));
});

test("the hook is committed executable, so a fresh clone runs it once core.hooksPath is set", async () => {
    const { origin } = await publishedLibrary();
    const fresh = path.join(tmpdir(), "fresh");
    mustGit(path.dirname(fresh), "clone", "-q", origin, fresh);

    assert.strictEqual(mustGit(fresh, "ls-files", "--stage", ".githooks/pre-push").split(" ")[0], "100755");
    assert.strictEqual(hooksPath(fresh), null, "a clone has no core.hooksPath until someone sets it");
});

test("a library below the top of its repository, in a directory with a space, gets the hook at the top, and the hook checks that library", async () => {
    const { top, origin, dir } = await publishedLibrary("my apis/orders");
    assert.ok(fs.existsSync(path.join(top, ".githooks", "pre-push")));
    assert.ok(!fs.existsSync(path.join(dir, ".githooks")), "git only runs hooks from the top of the repository");

    edit(dir, FRAGMENT, "List examples.", "List every example.");
    commitAll(top, "change the specification, not the version");

    const push = git(top, "push", "origin", "topic");

    assert.ok(!push.ok, "the push went through");
    assert.match(push.out, /but its version is still 0\.1\.0: raise it in my apis\/orders\/specs\/openapi\/bundles\/example-service\.bundle\.properties/);
    assert.ok(!pushed(origin, "topic"));
});

test("with --runner docker, the hook runs this Publisher's image on the whole repository, naming the library", async () => {
    const { top } = clone();
    await init(path.join(top, "api"), "--runner", "docker");
    commitAll(top, "scaffold");
    fs.rmSync(path.join(BIN, "docker-called"), { force: true });

    mustGit(top, "push", "-q", "origin", "main");

    const [cwd, ...args] = fs.readFileSync(path.join(BIN, "docker-called"), "utf8").trimEnd().split("\n");
    assert.strictEqual(cwd, top, "docker ran outside the top of the repository");
    // The whole repository, .git included, so check can read its history and tags.
    assert.deepStrictEqual(args.slice(args.indexOf("-v"), args.indexOf("-v") + 2), ["-v", `${top}:/work`]);
    assert.ok(args.includes(`arcetect/api-only-publisher:${imageTag(require("../package.json").version)}`));
    assert.deepStrictEqual(args.slice(-3), ["check", "-C", "api"]);
});

test("the Docker image is the one of this Publisher's version, or latest for a Publisher that was never released", () => {
    assert.strictEqual(imageTag("0.18.5"), "0.18.5");
    assert.strictEqual(imageTag("0.0.0"), "latest");
    assert.strictEqual(imageTag("0.0.0-local"), "latest");
});

for (const [manager, arrange] of [
    ["husky", (top) => fs.mkdirSync(path.join(top, ".husky"))],
    ["lefthook", (top) => fs.writeFileSync(path.join(top, "lefthook.yml"), "")],
    ["pre-commit", (top) => fs.writeFileSync(path.join(top, ".pre-commit-config.yaml"), "")],
    ["core.hooksPath (tools/hooks)", (top) => mustGit(top, "config", "core.hooksPath", "tools/hooks")],
]) {
    test(`a repository whose hooks ${manager} runs gets no hook from init, only the command for its pre-push entry`, async () => {
        const { top } = clone();
        arrange(top);
        const before = hooksPath(top);

        const printed = await init(top);

        assert.ok(!fs.existsSync(path.join(top, ".githooks")), "init wrote a hook that competes with the manager");
        assert.strictEqual(hooksPath(top), before, "init changed where git looks for hooks");
        assert.ok(printed.includes(`  skipped    .githooks/pre-push (${manager} runs this repository's hooks)`));
        assert.ok(printed.includes("Add to its pre-push hook: npx --no-install api-only-publisher check"));
    });
}

test("a pre-push hook already there is the team's own: init leaves it, and --force replaces it with one that checks", async () => {
    const { top } = clone();
    fs.mkdirSync(path.join(top, ".githooks"));
    const hook = path.join(top, ".githooks", "pre-push");
    fs.writeFileSync(hook, "#!/bin/sh\nmake test\n");

    const printed = await init(top);
    assert.strictEqual(fs.readFileSync(hook, "utf8"), "#!/bin/sh\nmake test\n");
    assert.ok(printed.includes("  differs    .githooks/pre-push (left alone; --force overwrites)"));

    await init(top, "--force");
    assert.match(fs.readFileSync(hook, "utf8"), /^exec npx --no-install api-only-publisher check$/m);
    assert.ok((fs.statSync(hook).mode & 0o111) !== 0, "the replaced hook is not executable");
});

test("running init again changes neither the hook nor git's configuration", async () => {
    const { top } = clone();
    await init(top);
    const hook = fs.readFileSync(path.join(top, ".githooks", "pre-push"), "utf8");

    const printed = await init(top);

    assert.strictEqual(fs.readFileSync(path.join(top, ".githooks", "pre-push"), "utf8"), hook);
    assert.ok(printed.includes("  identical  .githooks/pre-push"));
    assert.ok(!printed.some((line) => line.startsWith("  configured")));
});

test("a second library in the same repository finds the first one's hook, and is told to add its check by hand", async () => {
    const { top } = clone();
    await init(path.join(top, "orders"));
    const printed = await init(path.join(top, "customers"));

    assert.match(fs.readFileSync(path.join(top, ".githooks", "pre-push"), "utf8"), /^cd orders \|\| exit 1$/m);
    assert.ok(printed.includes("  differs    ../.githooks/pre-push (left alone; --force overwrites)"));
});

test("outside a git repository, the hook is written beside apionly.yaml, to be enabled once there is one", async () => {
    const dir = path.join(tmpdir(), "lib");
    const printed = await init(dir);

    assert.ok(fs.existsSync(path.join(dir, ".githooks", "pre-push")));
    assert.ok(printed.includes("Once this is a git repository, run the pre-push hook: git config core.hooksPath .githooks"));
});

test("--runner takes npm or docker, and init writes nothing for anything else", async () => {
    const dir = path.join(tmpdir(), "lib");
    await assert.rejects(init(dir, "--runner", "podman"), /--runner 'podman' is not npm or docker/);
    assert.ok(!fs.existsSync(dir));
});
