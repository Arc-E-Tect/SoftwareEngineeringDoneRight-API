"use strict";

// `split` -- make a fragment library safe to break into separate repositories.
//
// The problem it solves is real and easy to miss. A fragment library is one $ref
// graph, and that graph does not respect the directory boundaries a repository
// split would follow. An AsyncAPI event schema that reuses an OpenAPI common
// schema -- a username, say -- means the same thing over a message broker as over
// HTTP, and that reuse is a reference across the two trees. Move the trees into
// separate repositories as they stand and every such reference dangles.
//
// `split` resolves each part's dependency closure and materialises it whole:
// each part gets its own subtree *plus* a copy of every foreign file it reaches,
// so that after the split each side has a complete set of definitions and builds
// on its own.
//
// The copies keep their original path relative to the shared source root, which
// is what makes this safe: every relative $ref keeps resolving exactly as it did,
// so nothing has to be rewritten and no reference can be broken by a rewrite
// getting the depth wrong.
//
// What this cannot do is keep the copies in step afterwards. Duplicating a schema
// is the price of independent repositories, not a way of avoiding it -- see the
// note written into each part.

const fs = require("fs");
const path = require("path");

const { forTargets, resolve } = require("./closure");

class SplitError extends Error {}

const NOTE = `= Imported shared fragments

The files under this directory are **copies**, taken from another part of the
specification library this repository was split out of.

They are here because this repository's own fragments \`$ref\` them: at the point
of the split, the library was a single \`$ref\` graph that did not follow the
directory boundary the split was made along. Copying them is what makes this
repository self-contained and buildable on its own.

They keep the path they had before the split, relative to the old shared source
root, so every existing relative \`$ref\` still resolves and nothing had to be
rewritten.

== What this costs

These copies can now drift from the originals, and nothing here will notice.
A shared schema that two repositories both define is two schemas that happen to
agree today.

If that matters for a given fragment -- and for something like a username or a
problem-response shape it usually does -- promote it to a contract of its own,
published and consumed like any other, rather than copied. That is what the
API-Only Subscriber is for.

== Imported files

`;

/**
 * Group targets into parts.
 *
 * `kind` splits along the specification types, which is the boundary a
 * repository split usually follows. `target` gives every target its own
 * self-contained tree, which is what you want before splitting into
 * per-service repositories.
 */
function partition(config, by) {
    const parts = new Map();

    if (by === "kind") {
        for (const kind of ["openapi", "asyncapi"]) {
            const targets = config.targetsFor(kind);
            if (targets.length > 0) parts.set(kind, targets.map((t) => ({ target: t, kind })));
        }
        return parts;
    }

    if (by === "target") {
        for (const target of Object.keys(config.targets)) {
            const kinds = ["openapi", "asyncapi"].filter((k) => config.targets[target][k]);
            parts.set(target, kinds.map((k) => ({ target, kind: k })));
        }
        return parts;
    }

    throw new SplitError(`unknown --by '${by}'; expected 'kind' or 'target'`);
}

/**
 * Which subtree of the shared source root a file naturally belongs to.
 *
 * The first path segment under the root: `openapi/...` or `asyncapi/...`.
 */
function homeSubtree(relPath) {
    return relPath.split("/")[0];
}

/**
 * @returns {Array<{part, dir, own: string[], imported: string[]}>}
 */
function split(config, { by = "kind", outDir, log = () => {} } = {}) {
    if (!outDir) throw new SplitError("split requires an output directory");

    const parts = partition(config, by);
    const results = [];

    for (const [partName, members] of parts) {
        // Union of every member's closure.
        const files = new Set();
        for (const { target, kind } of members) {
            const entry = config.bundlePath(target, kind);
            if (!fs.existsSync(entry)) {
                throw new SplitError(
                    `target '${target}': ${kind} bundle root not found at ${entry}; build first so the tree is staged`
                );
            }
            for (const file of resolve(entry).files) files.add(file);
        }

        const partDir = path.join(outDir, partName);
        fs.rmSync(partDir, { recursive: true, force: true });

        const own = [];
        const imported = [];

        for (const file of [...files].sort()) {
            // Every closure file lives under one of the staging roots; map it back
            // to its path relative to the shared source root.
            let rel = null;
            for (const kind of ["openapi", "asyncapi"]) {
                const candidate = path.relative(config.stagingRoot(kind), file);
                if (!candidate.startsWith("..") && !path.isAbsolute(candidate)) {
                    rel = candidate.split(path.sep).join("/");
                    break;
                }
            }
            if (rel === null) {
                throw new SplitError(`${file} is outside every staging root; cannot place it in a part`);
            }

            // Preserving the relative path is the whole trick: the copy sits where
            // the $refs already expect to find it.
            const dest = path.join(partDir, rel);
            fs.mkdirSync(path.dirname(dest), { recursive: true });
            fs.copyFileSync(file, dest);

            // A file is "imported" when it comes from a subtree this part does not
            // own. With --by target every part owns both subtrees, so nothing is
            // imported and the split is clean by construction.
            const foreign = by === "kind" && homeSubtree(rel) !== config.sources[partName];
            (foreign ? imported : own).push(rel);
        }

        if (imported.length > 0) {
            fs.writeFileSync(
                path.join(partDir, "IMPORTED.adoc"),
                NOTE + imported.map((r) => `* \`${r}\`\n`).join("")
            );
        }

        log(
            `${partName.padEnd(14)} ${String(own.length).padStart(3)} own` +
            (imported.length > 0 ? `, ${imported.length} imported` : "")
        );
        for (const rel of imported) log(`               imported: ${rel}`);

        results.push({ part: partName, dir: partDir, own, imported });
    }

    return results;
}

module.exports = { split, partition, SplitError };
