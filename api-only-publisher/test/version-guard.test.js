"use strict";

// A changed specification comes with a changed version. The guard decides that from git
// alone: a target's release tag when there is one, otherwise the merge-base with the main
// branch. Tested against real git histories, as `changed` is.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { load, ConfigError } = require("../src/config");
const { guard, GuardError } = require("../src/version-guard");

const CONFIG = (extra = "") => `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
defaults:
  openapi:
    outputName: openapi.yaml
build:
  staging: build/staging
${extra}targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
  beta:
    openapi:
      bundle: bundles/beta.yaml
`;

const FILES = {
    ".gitignore": "build/\n",
    "specs/openapi/bundles/alpha.yaml": "openapi: 3.1.1\npaths:\n  /a:\n    $ref: '../paths/A.yaml'\n",
    "specs/openapi/bundles/beta.yaml": "openapi: 3.1.1\npaths:\n  /b:\n    $ref: '../paths/B.yaml'\n",
    "specs/openapi/bundles/alpha.bundle.properties": "version=1.0.0\n",
    "specs/openapi/bundles/beta.bundle.properties": "version=2.0.0\n",
    "specs/openapi/paths/A.yaml": "get:\n  x: {}\n",
    "specs/openapi/paths/B.yaml": "get:\n  y: {}\n",
};

function git(dir, ...args) {
    return execFileSync("git", args, { cwd: dir, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

function write(dir, rel, content) {
    fs.mkdirSync(path.dirname(path.join(dir, rel)), { recursive: true });
    fs.writeFileSync(path.join(dir, rel), content);
}

/** A library on `main` with one commit, then on branch `topic`; `extra` goes into apionly.yaml. */
function repository(extra = "") {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-guard-"));
    write(dir, "apionly.yaml", CONFIG(extra));
    for (const [rel, content] of Object.entries(FILES)) write(dir, rel, content);
    git(dir, "init", "-q", "-b", "main");
    git(dir, "config", "user.email", "test@example.invalid");
    git(dir, "config", "user.name", "Test");
    git(dir, "config", "commit.gpgsign", "false");
    git(dir, "config", "tag.gpgsign", "false");
    git(dir, "add", "-A");
    git(dir, "commit", "-qm", "initial");
    git(dir, "checkout", "-qb", "topic");
    return dir;
}

function commit(dir, files, message = "change") {
    for (const [rel, content] of Object.entries(files)) write(dir, rel, content);
    git(dir, "add", "-A");
    git(dir, "commit", "-qm", message);
}

function verdicts(dir, options = {}) {
    const config = load(path.join(dir, "apionly.yaml"));
    return Object.fromEntries(guard(config, ["alpha", "beta"], options).map((r) => [r.target, r]));
}

const CHANGE_A = { "specs/openapi/paths/A.yaml": "get:\n  x: {}\n  z: {}\n" };

// ------------------------------------------------------------------ no release tag: the merge-base

test("without a release tag, a target changed since the merge-base needs a higher version than it had there", () => {
    const dir = repository();
    commit(dir, CHANGE_A);
    let result = verdicts(dir);
    assert.strictEqual(result.alpha.verdict, "violation");
    assert.match(result.alpha.reason, /changed since the merge-base with main \([0-9a-f]{12}\), but its version is still 1\.0\.0/);
    assert.match(result.alpha.reason, /specs\/openapi\/bundles\/alpha\.bundle\.properties/);
    assert.strictEqual(result.beta.verdict, "ok", "beta did not change");

    commit(dir, { "specs/openapi/bundles/alpha.bundle.properties": "version=1.1.0\n" });
    result = verdicts(dir);
    assert.strictEqual(result.alpha.verdict, "ok");
    assert.match(result.alpha.reason, /1\.0\.0 -> 1\.1\.0/);
});

test("a change not yet committed counts: the working tree is compared", () => {
    const dir = repository();
    write(dir, "specs/openapi/paths/A.yaml", CHANGE_A["specs/openapi/paths/A.yaml"]);
    assert.strictEqual(verdicts(dir).alpha.verdict, "violation");
});

test("a version lower than the merge-base's is refused, changed or not", () => {
    const dir = repository();
    commit(dir, { "specs/openapi/bundles/beta.bundle.properties": "version=1.9.0\n" });
    const result = verdicts(dir);
    assert.strictEqual(result.beta.verdict, "violation");
    assert.match(result.beta.reason, /1\.9\.0 is lower than 2\.0\.0/);
});

test("a target whose version file the merge-base does not have is new, and passes", () => {
    const dir = repository();
    git(dir, "checkout", "-q", "main");
    git(dir, "rm", "-q", "specs/openapi/bundles/alpha.bundle.properties");
    git(dir, "commit", "-qm", "no alpha version yet");
    git(dir, "checkout", "-q", "topic");
    git(dir, "reset", "-q", "--hard", "main");
    commit(dir, { ...CHANGE_A, "specs/openapi/bundles/alpha.bundle.properties": "version=0.1.0\n" });
    const result = verdicts(dir);
    assert.strictEqual(result.alpha.verdict, "ok");
    assert.match(result.alpha.reason, /new/);
});

test("--since names the ref to compare against instead of the merge-base", () => {
    const dir = repository();
    commit(dir, CHANGE_A);
    commit(dir, { "specs/openapi/bundles/alpha.bundle.properties": "version=1.1.0\n" });
    assert.strictEqual(verdicts(dir).alpha.verdict, "ok");
    assert.strictEqual(verdicts(dir, { since: "HEAD~1" }).alpha.verdict, "ok", "the bump is the only change since HEAD~1");
    commit(dir, { "specs/openapi/paths/A.yaml": "get:\n  x: {}\n  zz: {}\n" });
    assert.strictEqual(verdicts(dir, { since: "HEAD~1" }).alpha.verdict, "violation", "changed again since HEAD~1, still 1.1.0");
});

test("versioning.mainBranch names the branch the merge-base is taken with, and origin's copy is preferred", () => {
    const dir = repository("versioning:\n  mainBranch: trunk\n");
    git(dir, "branch", "-q", "trunk", "main");
    commit(dir, CHANGE_A);
    assert.match(verdicts(dir).alpha.reason, /merge-base with trunk/);
    // origin/<branch> is what a CI checkout has; a remote-tracking ref stands in for it.
    git(dir, "update-ref", "refs/remotes/origin/trunk", "HEAD");
    assert.strictEqual(verdicts(dir).alpha.verdict, "ok", "nothing changed since origin/trunk");
});

test("a merge-base that cannot be found is an error that says what to fetch", () => {
    const dir = repository("versioning:\n  mainBranch: nowhere\n");
    const config = load(path.join(dir, "apionly.yaml"));
    assert.throws(() => guard(config, ["alpha"]), (e) => e instanceof GuardError
        && /no merge-base with nowhere/.test(e.message) && /fetch-depth: 0/.test(e.message));
});

// ------------------------------------------------------------------ release tags

test("a version with a release tag is exact: unchanged since the tag passes, changed is refused", () => {
    const dir = repository();
    git(dir, "tag", "alpha-v1.0.0");
    assert.strictEqual(verdicts(dir).alpha.verdict, "ok");
    assert.match(verdicts(dir).alpha.reason, /unchanged since its release tag alpha-v1\.0\.0/);
    commit(dir, CHANGE_A);
    const result = verdicts(dir);
    assert.strictEqual(result.alpha.verdict, "violation");
    assert.match(result.alpha.reason, /1\.0\.0 was released as alpha-v1\.0\.0, and alpha has changed since/);
});

test("a version above the latest release tag passes, changed or not; one below it is refused", () => {
    const dir = repository();
    git(dir, "tag", "alpha-v1.0.0");
    commit(dir, { ...CHANGE_A, "specs/openapi/bundles/alpha.bundle.properties": "version=1.1.0\n" });
    assert.strictEqual(verdicts(dir).alpha.verdict, "ok");
    assert.match(verdicts(dir).alpha.reason, /above its latest release, 1\.0\.0/);
    git(dir, "tag", "alpha-v1.2.0");
    const result = verdicts(dir);
    assert.strictEqual(result.alpha.verdict, "violation");
    assert.match(result.alpha.reason, /1\.1\.0 is lower than its latest release, 1\.2\.0/);
});

test("release tags decide over the merge-base, and another target's tags are not this one's", () => {
    const dir = repository();
    git(dir, "tag", "alpha-extra-v9.0.0");
    git(dir, "tag", "beta-v2.0.0");
    commit(dir, CHANGE_A);
    // alpha has no tag of its own, so the merge-base decides; alpha-extra's tag is not alpha's.
    assert.match(verdicts(dir).alpha.reason, /merge-base/);
    assert.match(verdicts(dir).beta.reason, /beta-v2\.0\.0/);
});

test("versioning.tagFormat names the tags", () => {
    const dir = repository("versioning:\n  tagFormat: \"api/{target}/{version}\"\n");
    git(dir, "tag", "alpha-v1.0.0");
    git(dir, "tag", "api/alpha/1.0.0");
    commit(dir, CHANGE_A);
    assert.match(verdicts(dir).alpha.reason, /released as api\/alpha\/1\.0\.0/);
});

test("a tag whose version is not semver is ignored", () => {
    const dir = repository();
    git(dir, "tag", "alpha-vnext");
    commit(dir, CHANGE_A);
    assert.match(verdicts(dir).alpha.reason, /merge-base/);
});

// ------------------------------------------------------------------ pre-releases

test("a pre-release of a released version is refused: it would sort before the release", () => {
    const dir = repository();
    git(dir, "tag", "alpha-v1.0.0");
    const result = verdicts(dir, { preRelease: "rc.1" });
    assert.strictEqual(result.alpha.verdict, "violation");
    assert.match(result.alpha.reason, /1\.0\.0-rc\.1 is a pre-release of 1\.0\.0, which is already released as alpha-v1\.0\.0/);
});

test("a pre-release tag is exact as a release tag is: the same pre-release with other specifications is refused", () => {
    const dir = repository();
    commit(dir, { "specs/openapi/bundles/alpha.bundle.properties": "version=1.1.0\n" });
    git(dir, "tag", "alpha-v1.1.0-rc.1");
    assert.strictEqual(verdicts(dir, { preRelease: "rc.1" }).alpha.verdict, "ok");
    commit(dir, CHANGE_A);
    assert.strictEqual(verdicts(dir, { preRelease: "rc.1" }).alpha.verdict, "violation");
    assert.strictEqual(verdicts(dir, { preRelease: "rc.2" }).alpha.verdict, "ok", "a new pre-release");
});

test("without tags, a pre-release needs no new version in the file: its identifiers make it new", () => {
    const dir = repository();
    commit(dir, CHANGE_A);
    const result = verdicts(dir, { preRelease: "rc.1" });
    assert.strictEqual(result.alpha.verdict, "ok");
    assert.match(result.alpha.reason, /pre-release 1\.0\.0-rc\.1/);
});

// ------------------------------------------------------------------ where it cannot run

test("outside a git repository the guard cannot decide, and says so", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-guard-nogit-"));
    write(dir, "apionly.yaml", CONFIG());
    for (const [rel, content] of Object.entries(FILES)) write(dir, rel, content);
    const config = load(path.join(dir, "apionly.yaml"));
    assert.throws(() => guard(config, ["alpha"]), (e) => e instanceof GuardError && e.notARepository
        && /not in a git repository/.test(e.message));
});

test("versioning is checked when apionly.yaml is read", () => {
    for (const [extra, expected] of [
        ["versioning:\n  tagFormat: \"{target}\"\n", /versioning\.tagFormat must contain \{target\} and \{version\}/],
        ["versioning:\n  other: 1\n", /unknown key 'other' in versioning/],
    ]) {
        const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-guard-config-"));
        write(dir, "apionly.yaml", CONFIG(extra));
        assert.throws(() => load(path.join(dir, "apionly.yaml")), (e) => e instanceof ConfigError && expected.test(e.message));
    }
});
