"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { substitute, PlaceholderError } = require("../src/placeholders");

function tmpdir() {
    return fs.mkdtempSync(path.join(os.tmpdir(), "aop-placeholders-"));
}

test("substitutes a token from a Markdown file", () => {
    const dir = tmpdir();
    fs.writeFileSync(path.join(dir, "intro.md"), "Hello.\n");
    const { content, resolved } = substitute("description: {{intro}}", { searchRoot: dir });
    assert.strictEqual(content, "description: Hello.");
    assert.deepStrictEqual(resolved, ["intro"]);
});

test("searches from the given root, not the input file's directory", () => {
    // The defect this fixes: the original tool searched downward from the input
    // file's own directory, so a snippet one level up was invisible and the
    // workaround was to move the Markdown files.
    const dir = tmpdir();
    const nested = path.join(dir, "a", "b");
    fs.mkdirSync(nested, { recursive: true });
    fs.writeFileSync(path.join(nested, "deep.md"), "found\n");
    const { content } = substitute("x: {{deep}}", { searchRoot: dir });
    assert.strictEqual(content, "x: found");
});

test("an unresolved token is an error, not a marker in the output", () => {
    // The defect this fixes: the original emitted *MISSING CONTENT* and warned,
    // so a broken document shipped from a green build.
    const dir = tmpdir();
    assert.throws(
        () => substitute("x: {{nope}}", { searchRoot: dir }),
        (error) => error instanceof PlaceholderError && /nope\.md/.test(error.message)
    );
});

test("non-strict mode leaves an unresolved token untouched and reports it", () => {
    const dir = tmpdir();
    const { content, unresolved } = substitute("x: {{nope}}", { searchRoot: dir, strict: false });
    assert.strictEqual(content, "x: {{nope}}");
    assert.deepStrictEqual(unresolved, ["nope"]);
    assert.ok(!content.includes("MISSING CONTENT"));
});

test("token names may contain dashes and dots", () => {
    // The defect this fixes: \w+ silently excluded these, so {{status-codes}}
    // was left in the output rather than reported as unresolved.
    const dir = tmpdir();
    fs.writeFileSync(path.join(dir, "status-codes.md"), "codes\n");
    fs.writeFileSync(path.join(dir, "v1.2.md"), "dotted\n");
    assert.strictEqual(substitute("a: {{status-codes}}", { searchRoot: dir }).content, "a: codes");
    assert.strictEqual(substitute("b: {{v1.2}}", { searchRoot: dir }).content, "b: dotted");
});

test("multi-line content keeps the placeholder's YAML indentation", () => {
    const dir = tmpdir();
    fs.writeFileSync(path.join(dir, "block.md"), "line one\nline two\n\nline four\n");
    const { content } = substitute("description: |\n    {{block}}\n", { searchRoot: dir });
    assert.strictEqual(content, "description: |\n    line one\n    line two\n\n    line four\n");
});

test("a brace that is not a token is left alone", () => {
    const dir = tmpdir();
    const text = "a: { not: a token }\nb: {{ spaced }}\n";
    assert.strictEqual(substitute(text, { searchRoot: dir, strict: false }).content, text);
});
