"use strict";

// Fragments no target reaches.
//
// A linter checks documents, and a fragment reaches a document only through a
// $ref. A fragment that no target references is therefore never linted, however
// wrong it is, until the day a target starts to use it. Looking for unreferenced
// fragments is how that is caught before then -- and how a definition left behind
// by a change that stopped using it is found at all.

const fs = require("fs");
const path = require("path");

const { forTargets } = require("./closure");

function yamlFiles(dir) {
    const files = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) files.push(...yamlFiles(full));
        else if (entry.isFile() && /\.ya?ml$/.test(entry.name)) files.push(full);
    }
    return files;
}

const portable = (file) => file.split(path.sep).join("/");

/**
 * Every YAML file under the source root that no target's closure reaches,
 * relative to the source root and sorted.
 *
 * Every target counts, `publish: false` ones included: a documentation view is
 * linted like any other target, so whatever it reaches is linted too. The staged
 * tree must already exist, as for forTargets.
 *
 * @returns {string[]}
 */
function unreferenced(config) {
    const reached = new Set();
    for (const [, entry] of forTargets(config)) {
        for (const [kind, files] of Object.entries(entry.byKind)) {
            const stagingRoot = config.stagingRoot(kind);
            for (const file of files) reached.add(portable(path.relative(stagingRoot, file)));
        }
    }
    const sourceRoot = config.sourceRoot();
    return yamlFiles(sourceRoot)
        .map((file) => portable(path.relative(sourceRoot, file)))
        .filter((file) => !reached.has(file))
        .sort();
}

module.exports = { unreferenced };
