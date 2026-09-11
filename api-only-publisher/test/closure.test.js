"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { resolve, hash, ClosureError } = require("../src/closure");

function tree(files) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-closure-"));
    for (const [rel, content] of Object.entries(files)) {
        const file = path.join(dir, rel);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, content);
    }
    return dir;
}

test("follows $refs transitively", () => {
    const dir = tree({
        "bundle.yaml": "paths:\n  /a:\n    $ref: 'paths/A.yaml'\n",
        "paths/A.yaml": "get:\n  responses:\n    '200':\n      $ref: '../schemas/S.yaml'\n",
        "schemas/S.yaml": "type: string\n",
        "unreferenced.yaml": "type: integer\n",
    });
    const { files } = resolve(path.join(dir, "bundle.yaml"));
    const rel = files.map((f) => path.relative(dir, f)).sort();
    assert.deepStrictEqual(rel, ["bundle.yaml", "paths/A.yaml", "schemas/S.yaml"]);
});

test("crosses directory boundaries, which is why a repository split needs care", () => {
    // The real library does exactly this: AsyncAPI event schemas reference an
    // OpenAPI common schema, so the two trees are one graph.
    const dir = tree({
        "asyncapi/bundle.yaml": "channels:\n  $ref: 'msg/M.yaml'\n",
        "asyncapi/msg/M.yaml": "payload:\n  $ref: '../../openapi/components/Username.yaml'\n",
        "openapi/components/Username.yaml": "type: string\n",
    });
    const { files } = resolve(path.join(dir, "asyncapi/bundle.yaml"));
    assert.ok(files.some((f) => f.endsWith(path.join("openapi", "components", "Username.yaml"))));
});

test("a document-root pointer adds nothing to the closure", () => {
    const dir = tree({
        "bundle.yaml": "operations:\n  send:\n    channel:\n      $ref: '#/channels/auditV1'\n",
    });
    const { files } = resolve(path.join(dir, "bundle.yaml"));
    assert.strictEqual(files.length, 1);
});

test("a $ref with a pointer after the path still contributes the file", () => {
    const dir = tree({
        "bundle.yaml": "a:\n  $ref: 'other.yaml#/components/schemas/X'\n",
        "other.yaml": "components: {}\n",
    });
    const { files } = resolve(path.join(dir, "bundle.yaml"));
    assert.strictEqual(files.length, 2);
});

test("a cycle terminates", () => {
    const dir = tree({
        "a.yaml": "x:\n  $ref: 'b.yaml'\n",
        "b.yaml": "y:\n  $ref: 'a.yaml'\n",
    });
    const { files } = resolve(path.join(dir, "a.yaml"));
    assert.strictEqual(files.length, 2);
});

test("a dangling $ref is an error", () => {
    const dir = tree({ "a.yaml": "x:\n  $ref: 'missing.yaml'\n" });
    assert.throws(() => resolve(path.join(dir, "a.yaml")), (e) => e instanceof ClosureError);
});

test("the hash is stable across orderings and sensitive to content", () => {
    const dir = tree({ "a.yaml": "one\n", "b.yaml": "two\n" });
    const a = path.join(dir, "a.yaml");
    const b = path.join(dir, "b.yaml");

    assert.strictEqual(hash([a, b], dir), hash([b, a], dir));

    const before = hash([a, b], dir);
    fs.writeFileSync(b, "changed\n");
    assert.notStrictEqual(hash([a, b], dir), before);
});

test("the hash covers Markdown snippets, so prose changes are detected", () => {
    // The closure is computed over the staged, substituted tree precisely so that
    // a change to shared prose counts as a change to the published document.
    const dir = tree({ "info.yaml": "description: filled in\n" });
    const file = path.join(dir, "info.yaml");
    const before = hash([file], dir);
    fs.writeFileSync(file, "description: filled in differently\n");
    assert.notStrictEqual(hash([file], dir), before);
});
