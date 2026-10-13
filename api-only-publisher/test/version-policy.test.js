"use strict";

const test = require("node:test");
const assert = require("node:assert");

const { parse, isPrerelease, npmDistTag, describe: describeVersion, VersionError } =
    require("../src/version-policy");

test("parses the parts of a semantic version", () => {
    assert.deepStrictEqual(parse("2.1.3"), { major: 2, minor: 1, patch: 3, prerelease: null, build: null });
    assert.deepStrictEqual(parse("2.1.3-rc.1"),
        { major: 2, minor: 1, patch: 3, prerelease: "rc.1", build: null });
    assert.deepStrictEqual(parse("2.1.3-rc.1+sha.abc"),
        { major: 2, minor: 1, patch: 3, prerelease: "rc.1", build: "sha.abc" });
});

test("a version that is not semver is refused, and says why it matters", () => {
    for (const bad of ["", "1", "1.2", "v1.2.3", "1.2.3.4", "latest"]) {
        assert.throws(() => parse(bad),
            (error) => error instanceof VersionError && /is not a semantic version/.test(error.message),
            `expected ${JSON.stringify(bad)} to be refused`);
    }
});

test("recognises a pre-release", () => {
    assert.strictEqual(isPrerelease("1.0.0"), false);
    assert.strictEqual(isPrerelease("1.0.0+build.5"), false);
    assert.strictEqual(isPrerelease("1.0.0-rc.1"), true);
    assert.strictEqual(isPrerelease("2.0.0-alpha.3"), true);
});

test("treats Maven's -SNAPSHOT as a pre-release, though it is not semver-legal", () => {
    // Recognised rather than rejected, because it is what Maven consumers expect
    // and refusing it would push them off the tool rather than onto semver.
    assert.strictEqual(isPrerelease("1.0.0-SNAPSHOT"), true);
    assert.strictEqual(describeVersion("1.0.0-SNAPSHOT").kind, "snapshot");
});

test("a pre-release never goes to the latest dist-tag", () => {
    // This is the whole mechanism preventing `npm install <pkg>` from quietly
    // picking up a contract that is not finished.
    assert.strictEqual(npmDistTag("1.0.0"), "latest");
    assert.strictEqual(npmDistTag("1.0.0-rc.1"), "next");
    assert.strictEqual(npmDistTag("1.0.0-SNAPSHOT"), "next");
});

test("classifies what kind of pre-release a version is", () => {
    assert.deepStrictEqual(describeVersion("1.0.0"), { prerelease: false, kind: "release" });
    assert.deepStrictEqual(describeVersion("1.0.0-rc.2"), { prerelease: true, kind: "rc" });
    assert.deepStrictEqual(describeVersion("1.0.0-alpha.1"), { prerelease: true, kind: "other" });
});

test("versions compare by semver precedence, build metadata ignored", () => {
    const { compare } = require("../src/version-policy");
    const ascending = ["0.9.0", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
        "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.2.0", "1.10.0", "2.0.0"];
    for (let i = 0; i < ascending.length - 1; i++) {
        assert.ok(compare(ascending[i], ascending[i + 1]) < 0, `${ascending[i]} < ${ascending[i + 1]}`);
        assert.ok(compare(ascending[i + 1], ascending[i]) > 0, `${ascending[i + 1]} > ${ascending[i]}`);
    }
    assert.strictEqual(compare("1.0.0+build.1", "1.0.0+build.2"), 0);
    assert.strictEqual(compare("1.0.0", "1.0.0"), 0);
});
