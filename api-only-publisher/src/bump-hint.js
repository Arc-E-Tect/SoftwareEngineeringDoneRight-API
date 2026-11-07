"use strict";

// Which part of a version a change to a target's documents most likely asks for.
//
// A hint, not an analysis: it walks the two documents and sorts each difference into
// documentation (patch), something added (minor), or something removed or changed
// (major), and the largest one wins. It knows no compatibility rules beyond that, so a
// change it calls a major may well be compatible; deciding that properly is #100.
//
// Below 1.0 each moves one place down: a breaking change bumps the minor version, an
// addition the patch version.

const { parse } = require("./version-policy");
const { canonical } = require("./documents");

const RANK = { patch: 1, minor: 2, major: 3 };

// Keys that only document: changing them changes what a reader is told, not what is accepted.
const DOCUMENTATION = new Set([
    "description", "summary", "title", "example", "examples", "externalDocs",
    "contact", "license", "termsOfService", "x-fragment-path",
]);

// Keys that narrow what is accepted: adding one is no addition.
const CONSTRAINTS = new Set([
    "required", "enum", "const", "type", "format", "pattern", "minimum", "maximum",
    "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength",
    "minItems", "maxItems", "uniqueItems", "minProperties", "maxProperties", "additionalProperties",
]);

// Maps whose keys are names the specification author chose: a property called
// `description` is a property, not documentation.
const NAMES = new Set([
    "properties", "patternProperties", "$defs", "definitions", "paths", "schemas", "responses",
    "parameters", "headers", "requestBodies", "securitySchemes", "links", "callbacks", "pathItems",
    "channels", "messages", "operations", "servers", "variables", "mapping", "content",
]);

function isObject(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
}

function pointer(at) {
    return "/" + at.map((part) => String(part).replace(/~/g, "~0").replace(/\//g, "~1")).join("/");
}

function basename(file) {
    return String(file).split("/").pop();
}

/** Every difference between `before` and `after`, as {bump, at, what}. */
function differences(before, after, at = [], parentKey = null, found = []) {
    const key = at.length > 0 ? at[at.length - 1] : null;
    const isKeyword = key !== null && typeof key === "string" && !NAMES.has(parentKey);
    const keyword = isKeyword ? key : null;

    if (canonical(before) === canonical(after)) return found;
    if (keyword === "x-fragment-path") {
        const renamed = before !== undefined && after !== undefined && basename(before) !== basename(after);
        found.push(renamed
            ? { bump: "major", at, what: "a fragment was renamed, which renames its generated class" }
            : { bump: "patch", at, what: "a fragment moved" });
        return found;
    }
    if (DOCUMENTATION.has(keyword)) {
        found.push({ bump: "patch", at, what: "documentation changed" });
        return found;
    }
    if (CONSTRAINTS.has(keyword)) {
        found.push({ bump: "major", at, what: "a constraint was added, removed or changed" });
        return found;
    }
    if (before === undefined) {
        found.push({ bump: "minor", at, what: "something was added" });
        return found;
    }
    if (after === undefined) {
        found.push({ bump: "major", at, what: "something was removed or changed" });
        return found;
    }
    if (isObject(before) && isObject(after)) {
        for (const k of new Set([...Object.keys(before), ...Object.keys(after)])) {
            differences(before[k], after[k], [...at, k], key, found);
        }
        return found;
    }
    if (Array.isArray(before) && Array.isArray(after)) {
        const kept = before.every((item, i) => i < after.length && canonical(item) === canonical(after[i]));
        found.push(kept
            ? { bump: "minor", at: [...at, before.length], what: "something was added" }
            : { bump: "major", at, what: "something was removed or changed" });
        return found;
    }
    found.push({ bump: "major", at, what: "something was removed or changed" });
    return found;
}

/**
 * The bump a change from `before` to `after` most likely asks for, from `version`.
 *
 * @param {Object<string, object>} before a target's documents, by kind, at the baseline
 * @param {Object<string, object>} after the same target's documents now
 * @param {string} version the version to bump from
 * @returns {{bump: "major"|"minor"|"patch", version: string, why: string} | null} null when nothing differs
 */
function bumpHint(before, after, version) {
    const found = [];
    for (const kind of [...new Set([...Object.keys(before), ...Object.keys(after)])].sort()) {
        differences(before[kind], after[kind], [], null, []).forEach((d) => found.push({ ...d, kind }));
    }
    if (found.length === 0) return null;
    const largest = found.reduce((a, b) => (RANK[b.bump] > RANK[a.bump] ? b : a));

    const v = parse(version);
    let bump = largest.bump;
    if (v.major === 0) bump = bump === "major" ? "minor" : "patch";
    const next = bump === "major" ? `${v.major + 1}.0.0`
        : bump === "minor" ? `${v.major}.${v.minor + 1}.0`
            : `${v.major}.${v.minor}.${v.patch + 1}`;
    const where = largest.at.length > 0 ? ` at ${pointer(largest.at)}` : ` (the ${largest.kind} document)`;
    return { bump, version: next, why: `${largest.what}${where}` };
}

module.exports = { bumpHint };
