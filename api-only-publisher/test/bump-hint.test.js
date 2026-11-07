"use strict";

// A hint, not an analysis: which part of the version a change to a target's documents
// most likely asks for. Documentation only is a patch, something added a minor, something
// removed or changed a major; below 1.0 each moves one place down (#121).

const test = require("node:test");
const assert = require("node:assert");

const { bumpHint } = require("../src/bump-hint");

const IBAN = { type: "string", description: "An IBAN.", pattern: "^[A-Z]{2}" };

function doc(schemas, extra = {}) {
    return { openapi: { openapi: "3.1.1", info: { title: "Accounts" }, components: { schemas }, ...extra } };
}

test("the same documents need no bump", () => {
    assert.strictEqual(bumpHint(doc({ Iban: IBAN }), doc({ Iban: { ...IBAN } }), "1.0.0"), null);
});

test("documentation only is a patch: descriptions, summaries, titles and examples", () => {
    for (const changed of [
        { ...IBAN, description: "An International Bank Account Number." },
        { ...IBAN, examples: ["NL91ABNA0417164300"] },
        { ...IBAN, title: "IBAN" },
    ]) {
        const hint = bumpHint(doc({ Iban: IBAN }), doc({ Iban: changed }), "1.0.0");
        assert.strictEqual(hint.bump, "patch", JSON.stringify(changed));
        assert.strictEqual(hint.version, "1.0.1");
    }
    const info = bumpHint(doc({ Iban: IBAN }), { openapi: { ...doc({ Iban: IBAN }).openapi, info: { title: "Bank accounts" } } }, "1.0.0");
    assert.strictEqual(info.bump, "patch");
});

test("a property called description is not documentation", () => {
    const before = doc({ Account: { type: "object", properties: { iban: IBAN } } });
    const after = doc({ Account: { type: "object", properties: { iban: IBAN, description: { type: "string" } } } });
    assert.strictEqual(bumpHint(before, after, "1.0.0").bump, "minor");
});

test("something added is a minor: a new schema, a new property, a new operation", () => {
    const before = doc({ Iban: IBAN });
    const added = bumpHint(before, doc({ Iban: IBAN, Bic: { type: "string" } }), "1.4.2");
    assert.strictEqual(added.bump, "minor");
    assert.strictEqual(added.version, "1.5.0");
    assert.match(added.why, /added/);
    assert.match(added.why, /\/components\/schemas\/Bic/);
});

test("something removed or changed is a major", () => {
    const removed = bumpHint(doc({ Iban: IBAN, Bic: { type: "string" } }), doc({ Iban: IBAN }), "1.4.2");
    assert.strictEqual(removed.bump, "major");
    assert.strictEqual(removed.version, "2.0.0");
    assert.match(removed.why, /removed or changed/);
    assert.match(removed.why, /\/components\/schemas\/Bic/);

    assert.strictEqual(bumpHint(doc({ Iban: IBAN }), doc({ Iban: { ...IBAN, type: "integer" } }), "1.0.0").bump, "major");
    const list = (items) => doc({ Ibans: { type: "array", items } });
    assert.strictEqual(bumpHint(list(IBAN), list(true), "1.0.0").bump, "major", "a schema replaced by a boolean");
});

test("an added constraint is a major: it narrows what was accepted", () => {
    for (const changed of [{ ...IBAN, maxLength: 34 }, { ...IBAN, enum: ["x"] }, { ...IBAN, format: "iban" }]) {
        assert.strictEqual(bumpHint(doc({ Iban: IBAN }), doc({ Iban: changed }), "1.0.0").bump, "major", JSON.stringify(changed));
    }
    const optional = { type: "object", properties: { iban: IBAN } };
    const required = { ...optional, required: ["iban"] };
    assert.strictEqual(bumpHint(doc({ Account: optional }), doc({ Account: required }), "1.0.0").bump, "major");
});

test("a list that only grows at its end is an addition; any other change to it is a major", () => {
    const before = doc({}, { tags: [{ name: "accounts" }] });
    assert.strictEqual(bumpHint(before, doc({}, { tags: [{ name: "accounts" }, { name: "cards" }] }), "1.0.0").bump, "minor");
    assert.strictEqual(bumpHint(before, doc({}, { tags: [{ name: "cards" }] }), "1.0.0").bump, "major");
    const required = (r) => doc({ Account: { type: "object", required: r } });
    assert.strictEqual(bumpHint(required(["iban"]), required(["iban", "bic"]), "1.0.0").bump, "major", "required never only adds");
});

test("a fragment moved to another directory is a patch; renamed, it renames its generated class, a major", () => {
    const at = (p) => doc({ Iban: { "x-fragment-path": p, ...IBAN } });
    assert.strictEqual(bumpHint(at("openapi/foo/Iban.yaml"), at("openapi/bar/Iban.yaml"), "1.0.0").bump, "patch");
    assert.strictEqual(bumpHint(at("openapi/foo/Iban.yaml"), at("openapi/foo/AccountNumber.yaml"), "1.0.0").bump, "major");
});

test("the largest change decides", () => {
    const before = doc({ Iban: IBAN, Bic: { type: "string" } });
    const after = doc({ Iban: { ...IBAN, description: "Other." }, Currency: { type: "string" } });
    assert.strictEqual(bumpHint(before, after, "1.0.0").bump, "major", "Bic was removed");
});

test("below 1.0 a breaking change is a minor and an addition a patch", () => {
    const removed = bumpHint(doc({ Iban: IBAN, Bic: {} }), doc({ Iban: IBAN }), "0.3.1");
    assert.deepStrictEqual([removed.bump, removed.version], ["minor", "0.4.0"]);
    const added = bumpHint(doc({ Iban: IBAN }), doc({ Iban: IBAN, Bic: {} }), "0.3.1");
    assert.deepStrictEqual([added.bump, added.version], ["patch", "0.3.2"]);
});

test("every kind counts: an AsyncAPI document changed while the OpenAPI one did not", () => {
    const before = { ...doc({ Iban: IBAN }), asyncapi: { asyncapi: "3.1.0", channels: { a: { address: "a" } } } };
    const after = { ...doc({ Iban: IBAN }), asyncapi: { asyncapi: "3.1.0", channels: { a: { address: "a" }, b: { address: "b" } } } };
    assert.strictEqual(bumpHint(before, after, "1.0.0").bump, "minor");
    const dropped = bumpHint(before, doc({ Iban: IBAN }), "1.0.0");
    assert.strictEqual(dropped.bump, "major", "a kind no longer built is removed");
});

test("a pre-release version is hinted from its release", () => {
    assert.strictEqual(bumpHint(doc({}), doc({ Bic: {} }), "1.2.0-rc.1").version, "1.3.0");
});
