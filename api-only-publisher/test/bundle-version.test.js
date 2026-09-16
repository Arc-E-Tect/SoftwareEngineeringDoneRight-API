"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { load } = require("../src/config");
const { versionOf, BundleVersionError } = require("../src/bundle-version");

// A library with one target, svc, and -- when given -- its version file.
function library(versionFile) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-bundle-version-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"), `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
targets:
  svc:
    openapi:
      bundle: bundles/svc.yaml
`);
    if (versionFile !== undefined) {
        const file = path.join(dir, "specs/openapi/bundles/svc.bundle.properties");
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, versionFile);
    }
    return load(path.join(dir, "apionly.yaml"));
}

test("reads the version, ignoring comments, blank lines and surrounding whitespace", () => {
    const config = library("# The svc contract.\n! Also a comment.\n\n  version = 2.3.1  \n");
    assert.strictEqual(versionOf(config, "svc"), "2.3.1");
});

test("accepts the key: value spelling too", () => {
    assert.strictEqual(versionOf(library("version: 1.0.0\n"), "svc"), "1.0.0");
});

test("appends a pre-release to the declared version", () => {
    assert.strictEqual(versionOf(library("version=1.2.0\n"), "svc", { preRelease: "rc.1" }), "1.2.0-rc.1");
});

test("a missing version file names where it was expected, and how to point elsewhere", () => {
    assert.throws(() => versionOf(library(), "svc"), (e) =>
        e instanceof BundleVersionError &&
        /target 'svc': no version file at .*svc\.bundle\.properties/.test(e.message) &&
        /targets\.svc\.versionFile/.test(e.message));
});

test("a version file without a version is refused", () => {
    assert.throws(() => versionOf(library("# nothing here\nname=svc\n"), "svc"), /declares no version/);
});

test("a version that is not semantic is refused", () => {
    assert.throws(() => versionOf(library("version=1.0\n"), "svc"), /not a semantic version/);
});

test("a version file holding a pre-release is refused, pointing at --pre-release", () => {
    // The file holds what the next release will be; a pre-release is cut from the
    // command line, so the file never has to be edited -- and un-edited -- for one.
    assert.throws(() => versionOf(library("version=1.1.0-rc.1\n"), "svc"), /release version.*--pre-release/s);
});

test("a pre-release that does not make a semantic version is refused", () => {
    assert.throws(() => versionOf(library("version=1.0.0\n"), "svc", { preRelease: "rc 1" }), /--pre-release 'rc 1'/);
});
