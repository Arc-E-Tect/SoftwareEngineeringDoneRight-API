"use strict";

// x-fragment-path: which fragment a bundled component came from.
//
// Bundling flattens the library's directory tree into one components namespace,
// renaming on collision (`UserV1`, `UserV1-2`). The extension puts the provenance
// back, so a consumer -- the TranscriberJ above all -- never has to read meaning
// into a bundler's key.
//
// The bundler is not told anything; it is shown stamped fragments. It copies a
// fragment's top-level keys into whatever it produces from that fragment, so a
// stamp at the top of a file arrives on the component built from it. But the same
// is true of every fragment the bundler inlines -- paths, info, tags -- so the
// build stamps in two passes: every fragment first, to learn which ones became
// components, then only those. See pipeline.bundleWithFragmentPaths.

const fs = require("fs");
const path = require("path");
const YAML = require("yaml");

const KEY = "x-fragment-path";

class FragmentPathError extends Error {}

/**
 * Stamp YAML fragments under `root`, in place, with their path from `root`.
 *
 * The path uses forward slashes on every platform. A file whose root is not a
 * mapping -- a list of servers, say -- has nowhere to carry the key and is left
 * alone; nothing that becomes a component looks like that.
 *
 * @param {Set<string>|null} only the root-relative paths to stamp; all when null
 * @returns {string[]} the root-relative paths stamped, sorted
 */
function stampFiles(root, { only = null } = {}) {
    const stamped = [];
    const walk = (dir) => {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => (a.name < b.name ? -1 : 1))) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) {
                walk(full);
                continue;
            }
            if (!/\.ya?ml$/.test(entry.name)) continue;
            const rel = path.relative(root, full).split(path.sep).join("/");
            if (only && !only.has(rel)) continue;

            const doc = YAML.parseDocument(fs.readFileSync(full, "utf8"));
            if (!YAML.isMap(doc.contents)) continue;
            if (doc.contents.has(KEY)) {
                throw new FragmentPathError(`${rel} declares ${KEY}; the Publisher sets that key and a fragment may not`);
            }
            doc.contents.items.unshift(doc.createPair(KEY, rel));
            fs.writeFileSync(full, doc.toString());
            stamped.push(rel);
        }
    };
    walk(root);
    return stamped;
}

/**
 * The stamp on every component of a parsed document, keyed `<type>/<name>`, in
 * document order. A component with no stamp -- one written inline in the bundle
 * root, say -- maps to null.
 *
 * @returns {Map<string, string|null>}
 */
function componentPaths(document) {
    const result = new Map();
    for (const [type, entries] of Object.entries(document.components || {})) {
        for (const [name, value] of Object.entries(entries || {})) {
            const fragment = value && typeof value === "object" ? value[KEY] : undefined;
            result.set(`${type}/${name}`, fragment === undefined ? null : fragment);
        }
    }
    return result;
}

/**
 * Every stamp in a parsed document that is not directly on a component, with the
 * JSON pointer of the object carrying it.
 *
 * @returns {Array<{at: string, path: string}>}
 */
function strayPaths(document) {
    const stray = [];
    const visit = (node, pointer) => {
        if (Array.isArray(node)) {
            node.forEach((item, i) => visit(item, `${pointer}/${i}`));
            return;
        }
        if (!node || typeof node !== "object") return;
        const onComponent = /^\/components\/[^/]+\/[^/]+$/.test(pointer);
        if (Object.hasOwn(node, KEY) && !onComponent) {
            stray.push({ at: pointer || "/", path: node[KEY] });
        }
        for (const [key, value] of Object.entries(node)) {
            if (key === KEY) continue;
            visit(value, `${pointer}/${key.replace(/~/g, "~0").replace(/\//g, "~1")}`);
        }
    };
    visit(document, "");
    return stray;
}

module.exports = { stampFiles, componentPaths, strayPaths, FragmentPathError, KEY };
