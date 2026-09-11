"use strict";

const test = require("node:test");
const assert = require("node:assert");

const { stamp, VersionError } = require("../src/version");

test("sets info.version", () => {
    const out = stamp("openapi: 3.1.1\ninfo:\n  title: X\n  version: 1.0.0\n", "2.3.1");
    assert.match(out, /^ {2}version: 2\.3\.1$/m);
});

test("preserves the quoting style the bundler used", () => {
    assert.match(stamp("info:\n  version: '1.0.0'\n", "2.0.0"), /version: '2\.0\.0'/);
    assert.match(stamp("info:\n  version: 1.0.0\n", "2.0.0"), /version: 2\.0\.0/);
});

test("changes nothing else in the document", () => {
    const source = "openapi: 3.1.1\ninfo:\n  title: X\n  version: 1.0.0\n  description: |\n    A line mentioning version: 9.9.9 in prose.\npaths: {}\n";
    const out = stamp(source, "2.0.0");
    assert.strictEqual(out, source.replace("  version: 1.0.0", "  version: 2.0.0"));
    assert.ok(out.includes("version: 9.9.9 in prose"));
});

test("a document with no info block is an error", () => {
    assert.throws(() => stamp("openapi: 3.1.1\npaths: {}\n", "1.0.0"),
        (e) => e instanceof VersionError && /no top-level 'info'/.test(e.message));
});

test("an info block with no version is an error", () => {
    assert.throws(() => stamp("info:\n  title: X\n", "1.0.0"),
        (e) => e instanceof VersionError && /no 'version' key/.test(e.message));
});

test("an unparseable document is an error rather than a silent no-op", () => {
    assert.throws(() => stamp("info:\n  a: b\n   bad: indent\n", "1.0.0"),
        (e) => e instanceof VersionError);
});
