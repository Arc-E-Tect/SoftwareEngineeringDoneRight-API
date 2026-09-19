"use strict";

// Additively completing an apionly.yaml that already exists.
//
// `init` creates a file that is not there; this is the same rule applied to
// configuration. When a kind's support is missing from an existing apionly.yaml --
// in whole, because the library never had it, or in part, because a run was
// interrupted between writing the fragments and writing the config for them -- the
// slots it needs are added. What is already there, however it reads, is never
// touched: a `defaults.openapi` block with no `lint` is a choice, not an absence.
//
// The unit added is a *slot*: one of the handful of places a kind's support in
// apionly.yaml lives -- never a key inside one, which is why `slots()` below stops
// at the map a kind occupies and goes no further into it.

const YAML = require("yaml");

/** Where a kind's tool is pinned in `toolchain`; the two kinds do not share a key name. */
const TOOLCHAIN_KEY = { openapi: "redocly", asyncapi: "asyncapi" };
const TOOLCHAIN_PIN = { openapi: "@redocly/cli@2.52.0", asyncapi: "@asyncapi/cli@6.0.2" };

/**
 * The slots scaffold(v) would fill for one kind, in the order config(v) declares them,
 * each naming the sibling keys -- in the order to prefer -- a slot goes before when one
 * of them is already there. `openapi` always precedes `asyncapi`, in every section that
 * holds both; `asyncapi` precedes `placeholders`, the one section-closing key that is
 * always there.
 */
function slotsFor(kind, v) {
    const target = v.target;
    if (kind === "openapi") {
        return [
            { path: ["sources"], key: "openapi", value: "openapi", before: ["asyncapi"] },
            {
                path: ["defaults"], key: "openapi", before: ["asyncapi", "placeholders"],
                value: { lint: ".redocly.yaml", outputName: "openapi.yaml" },
            },
            { path: ["toolchain"], key: "redocly", value: TOOLCHAIN_PIN.openapi, before: ["asyncapi"] },
            {
                path: ["targets", target], key: "openapi", before: ["asyncapi"],
                value: { bundle: `bundles/${target}_openapi_structure.yaml` },
            },
        ];
    }
    return [
        { path: ["sources"], key: "asyncapi", value: "asyncapi", before: [] },
        { path: ["defaults"], key: "asyncapi", value: { outputName: "asyncapi.yaml" }, before: ["placeholders"] },
        { path: ["toolchain"], key: "asyncapi", value: TOOLCHAIN_PIN.asyncapi, before: [] },
        {
            path: ["targets", target], key: "asyncapi", before: [],
            value: { bundle: `bundles/${target}_asyncapi_structure.yaml` },
        },
    ];
}

/** A slot's YAML path, dot-separated, as the report names it. */
function slotName(slot) {
    return [...slot.path, slot.key].join(".");
}

/**
 * Sets `key: value` in the map at `parentPath`, before the first of `before` that is
 * already a sibling there, or at the end when none is. Creates `parentPath` itself,
 * as `Document#setIn` does, when it is not there yet -- there being nothing in a map
 * that does not exist yet to come before.
 */
function place(doc, parentPath, key, value, before) {
    const parent = doc.getIn(parentPath, true);
    if (YAML.isMap(parent)) {
        for (const candidate of before) {
            const sibling = parent.items.find((pair) => String(pair.key) === candidate);
            if (sibling) {
                parent.items.splice(parent.items.indexOf(sibling), 0, doc.createPair(key, value));
                return;
            }
        }
    }
    doc.setIn([...parentPath, key], value);
}

/**
 * Adds to `text` -- an existing apionly.yaml -- whatever configuration slot
 * scaffold(values) would write and this file lacks. Every value, key and comment
 * already there survives unchanged; a slot already present, however it reads, is
 * never touched, and neither is anything inside it.
 *
 * `portfolio`, unlike a kind, is never added on its own account: whether a
 * library is about to have more than one target, and so whether the question is
 * even asked, is the caller's decision, made once, before this runs -- give the
 * strategies it resolved here to have the section written with them, or leave
 * this out entirely to leave the section alone, present or not.
 *
 * @param {string} text apionly.yaml, as it is on disk
 * @param {object} v the values scaffold(values) was given, resolved: every value
 *     DEFAULTS has, `values`' own where it gives one -- the shape scaffold() itself
 *     works from, so a caller that already has that object need not rebuild it
 * @param {{paths: string, location: string}} [portfolio] the portfolio section to
 *     add, with every strategy resolved, when it is missing; omitted, the section
 *     is never considered at all, whether or not the file already has one
 * @returns {{text: string, added: string[]}} the edited text, and the slots added, in
 *     the order scaffold() declares them, portfolio last; `added` is empty, and
 *     `text` is `text` itself, when nothing was missing.
 */
function addMissingConfig(text, v, portfolio) {
    const doc = YAML.parseDocument(text);
    const added = [];

    // Not every file that differs is a library with a kind missing. One with a YAML
    // error cannot be edited at all -- parseDocument does not throw on one, it just
    // records it, and a document with errors refuses to be stringified back. One that
    // does not even declare sources.root, whatever it parses to, is not an apionly.yaml
    // this can complete either way; force, or a person, is what either file needs.
    if (doc.errors.length > 0 || !doc.hasIn(["sources", "root"])) return { text, added };

    for (const kind of ["openapi", "asyncapi"].filter((k) => v.kinds.includes(k))) {
        for (const slot of slotsFor(kind, v)) {
            if (doc.hasIn([...slot.path, slot.key])) continue;
            place(doc, slot.path, slot.key, slot.value, slot.before);
            added.push(slotName(slot));
        }
    }

    if (portfolio && !doc.hasIn(["portfolio"])) {
        doc.setIn(["portfolio"], {
            openapi: { paths: portfolio.paths, operationIds: "target-prefix", tags: "reconcile" },
            security: "push-down",
            location: portfolio.location,
        });
        added.push("portfolio");
    }

    return added.length === 0 ? { text, added } : { text: doc.toString(), added };
}

module.exports = { addMissingConfig };
