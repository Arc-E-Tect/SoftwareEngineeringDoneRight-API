"use strict";

// The Docker image's tag rules, as the release workflow applies them (V8): an
// exact-version tag is written once and never points at another digest; the
// floating tags move only for the highest released version; a pre-release gets
// its exact tag alone.

const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { plan, promotion, ImageTagError } = require("../docker/image-tags");

const DIGEST_A = `sha256:${"a".repeat(64)}`;
const DIGEST_B = `sha256:${"b".repeat(64)}`;

test("a new highest release is built, and moves every floating tag", () => {
    assert.deepStrictEqual(plan({ version: "1.4.2", released: ["1.3.0", "1.4.1", "1.4.2"] }), {
        version: "1.4.2", build: true, digest: null, floating: ["1.4", "1", "latest"],
    });
});

test("the released list need not name the version being released", () => {
    assert.deepStrictEqual(plan({ version: "2.0.0", released: ["1.9.9"] }).floating, ["2.0", "2", "latest"]);
    assert.deepStrictEqual(plan({ version: "0.14.0", released: [] }).floating, ["0.14", "0", "latest"]);
});

test("a re-publish of an older version moves no floating tag", () => {
    const older = plan({ version: "1.3.0", released: ["1.3.0", "1.4.2"] });
    assert.deepStrictEqual(older.floating, []);
    // Not even the tags of its own line: 1.3 moves only with the highest release.
    assert.deepStrictEqual(plan({ version: "1.3.1", released: ["1.3.1", "1.4.0"] }).floating, []);
});

test("a pre-release gets only its exact tag, and is never compared as the highest", () => {
    assert.deepStrictEqual(plan({ version: "2.0.0-rc.1", released: ["1.4.2"] }).floating, []);
    assert.deepStrictEqual(plan({ version: "1.4.3", released: ["1.4.2", "2.0.0-rc.1"] }).floating, ["1.4", "1", "latest"]);
});

test("an exact-version tag GHCR already has is reused, never rebuilt", () => {
    assert.deepStrictEqual(plan({ version: "1.4.2", released: ["1.4.2"], ghcrDigest: DIGEST_A }), {
        version: "1.4.2", build: false, digest: DIGEST_A, floating: ["1.4", "1", "latest"],
    });
    assert.deepStrictEqual(plan({ version: "1.3.0", released: ["1.3.0", "1.4.2"], ghcrDigest: DIGEST_A }), {
        version: "1.3.0", build: false, digest: DIGEST_A, floating: [],
    });
});

test("a version that is not semver, or a digest that is not one, is refused", () => {
    assert.throws(() => plan({ version: "1.4", released: [] }), ImageTagError);
    assert.throws(() => plan({ version: "1.4.2", released: ["1.x"] }), ImageTagError);
    assert.throws(() => plan({ version: "1.4.2", released: [], ghcrDigest: "latest" }), ImageTagError);
});

test("promotion copies a version Docker Hub does not have", () => {
    assert.strictEqual(promotion({ version: "1.4.2", sourceDigest: DIGEST_A, existingDigest: null }), "copy");
});

test("promotion of the digest Docker Hub already has is a no-op", () => {
    assert.strictEqual(promotion({ version: "1.4.2", sourceDigest: DIGEST_A, existingDigest: DIGEST_A }), "present");
});

test("promotion never re-points an exact-version tag at another digest", () => {
    assert.throws(() => promotion({ version: "1.4.2", sourceDigest: DIGEST_B, existingDigest: DIGEST_A }),
        (error) => error instanceof ImageTagError &&
            error.message.includes(`already points at ${DIGEST_A}`) && error.message.includes(DIGEST_B));
});

test("the CLI prints plan and promotion as GitHub step outputs", () => {
    const script = path.join(__dirname, "..", "docker", "image-tags.js");
    assert.strictEqual(execFileSync("node", [script, "plan", "--version", "1.4.2", "--released", "1.4.1 1.4.2",
        "--ghcr-digest", ""], { encoding: "utf8" }),
    "build=true\ndigest=\nfloating=1.4 1 latest\n");
    assert.strictEqual(execFileSync("node", [script, "plan", "--version", "1.3.0", "--released", "1.3.0\n1.4.2",
        "--ghcr-digest", DIGEST_A], { encoding: "utf8" }),
    `build=false\ndigest=${DIGEST_A}\nfloating=\n`);
    assert.strictEqual(execFileSync("node", [script, "promotion", "--version", "1.4.2", "--source-digest", DIGEST_A,
        "--existing-digest", DIGEST_A], { encoding: "utf8" }), "action=present\n");
    assert.throws(() => execFileSync("node", [script, "promotion", "--version", "1.4.2", "--source-digest", DIGEST_B,
        "--existing-digest", DIGEST_A], { stdio: "pipe" }), /already points at/);
    assert.throws(() => execFileSync("node", [script, "nonsense"], { stdio: "pipe" }), /usage/);
});
