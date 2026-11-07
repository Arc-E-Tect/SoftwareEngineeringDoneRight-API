"use strict";

// A target's documents as the guard compares them: parsed, without the version stamped
// on them, and canonical, so that only what a consumer can tell apart counts (#121).

const test = require("node:test");
const assert = require("node:assert");

const { canonical, documentOf, sameDocuments } = require("../src/documents");

test("canonical JSON sorts keys at every level and keeps the order of lists", () => {
    assert.strictEqual(canonical({ b: 1, a: { d: [2, 1], c: null } }), '{"a":{"c":null,"d":[2,1]},"b":1}');
    assert.strictEqual(canonical("é"), '"é"');
});

test("a document is its parsed content: comments, quoting and layout do not count", () => {
    const one = documentOf("# Accounts\nopenapi: 3.1.1\ninfo:\n  title: Accounts\npaths: {}\n");
    const other = documentOf("{openapi: '3.1.1', paths: {}, info: {title: \"Accounts\"}}\n");
    assert.deepStrictEqual(one, other);
});

test("the version stamped on a document is not part of it", () => {
    const stamped = documentOf("openapi: 3.1.1\ninfo:\n  title: Accounts\n  version: 1.2.3\n");
    assert.deepStrictEqual(stamped, { openapi: "3.1.1", info: { title: "Accounts" } });
    assert.deepStrictEqual(documentOf("asyncapi: 3.1.0\ninfo:\n  version: 0.0.0\n"), { asyncapi: "3.1.0", info: {} });
});

test("a target's documents are the same when every kind is, in any key order", () => {
    const a = { openapi: { b: 1, a: 2 }, asyncapi: { x: 1 } };
    assert.ok(sameDocuments(a, { asyncapi: { x: 1 }, openapi: { a: 2, b: 1 } }));
    assert.ok(!sameDocuments(a, { openapi: { b: 1, a: 2 } }), "a kind fewer");
    assert.ok(!sameDocuments(a, { openapi: { b: 1, a: 3 }, asyncapi: { x: 1 } }));
});
