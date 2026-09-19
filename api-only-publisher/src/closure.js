"use strict";

// The dependency closure of a target.
//
// Independent per-target versioning has one non-obvious consequence, and this is
// the machinery that pays for it. A change under components/common/ affects every
// target that reaches it; a change under one product's own context affects only
// that one. Under repository-wide versioning that distinction is invisible,
// because everything bumps together. Under per-target versioning the release job
// has to work out which targets a commit actually changed.
//
// The closure is computed over the *staged, substituted* tree rather than the raw
// source, so that a change to a Markdown snippet -- which genuinely changes the
// published document -- is detected.

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

class ClosureError extends Error {}

// $ref values, in both the quoted and unquoted YAML forms the fragments use.
const REF = /\$ref:\s*(?:'([^']+)'|"([^"]+)"|([^\s#'"][^\s]*))/g;

/**
 * Every file reachable from `entry` by following $refs transitively.
 *
 * A JSON-pointer-only reference ("#/channels/auditV1") points inside the file
 * that contains it and adds nothing to the closure. A reference with a path
 * before the "#" contributes that file.
 *
 * @returns {string[]} absolute paths, sorted, including the entry file
 */
function resolve(entry, { onMissing = "throw" } = {}) {
    const seen = new Set();
    const missing = [];
    const queue = [path.resolve(entry)];

    while (queue.length > 0) {
        const file = queue.shift();
        if (seen.has(file)) continue;

        if (!fs.existsSync(file)) {
            missing.push(file);
            if (onMissing === "throw") {
                throw new ClosureError(`$ref target does not exist: ${file}`);
            }
            continue;
        }
        seen.add(file);

        let text;
        try {
            text = fs.readFileSync(file, "utf8");
        } catch {
            continue;
        }

        for (const match of text.matchAll(REF)) {
            const ref = match[1] || match[2] || match[3];
            if (!ref || ref.startsWith("#")) continue;
            const [relPath] = ref.split("#");
            if (!relPath) continue;
            queue.push(path.resolve(path.dirname(file), relPath));
        }
    }

    return { files: [...seen].sort(), missing };
}

/**
 * A content hash over a target's closure.
 *
 * Content-addressed and order-stable: each file contributes its path relative to
 * `root` and the SHA-256 of its bytes, in sorted order. Recording this alongside
 * a released version is what lets a later commit decide whether the target
 * actually changed, without re-reading the release.
 */
function hash(files, root) {
    const digest = crypto.createHash("sha256");
    for (const file of [...files].sort()) {
        const rel = path.relative(root, file).split(path.sep).join("/");
        digest.update(rel);
        digest.update("\0");
        digest.update(crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex"));
        digest.update("\n");
    }
    return digest.digest("hex");
}

/**
 * Closures for every target that declares a bundle of the given kinds.
 *
 * The staged tree must already exist; callers stage first so that the closure
 * covers substituted content. Pass `only` to compute closures for those targets
 * alone: a target outside it is never read, so its bundle root need not be staged.
 *
 * @param {string[]|null} only the targets to compute closures for; every target when null
 * @returns {Map<string, {files: string[], sha256: string, byKind: object}>}
 */
function forTargets(config, kinds = ["openapi", "asyncapi"], only = null) {
    const result = new Map();

    for (const kind of kinds) {
        for (const target of config.targetsFor(kind)) {
            if (only && !only.includes(target)) continue;
            const entry = config.bundleRootPath(target, kind);
            if (!fs.existsSync(entry)) {
                throw new ClosureError(
                    `target '${target}': ${kind} bundle root not found at ${entry}; stage the tree first`
                );
            }
            const { files } = resolve(entry);
            const existing = result.get(target) || { files: [], byKind: {} };
            existing.byKind[kind] = files;
            existing.files = [...new Set(existing.files.concat(files))].sort();
            result.set(target, existing);
        }
    }

    // Hash against the staging root of whichever kind the files came from, so
    // the recorded paths are stable across machines and checkouts.
    for (const [target, entry] of result) {
        const roots = Object.keys(entry.byKind).map((k) => config.stagingRoot(k));
        entry.sha256 = hash(entry.files, roots[0]);
        result.set(target, entry);
    }
    return result;
}

module.exports = { resolve, hash, forTargets, ClosureError, REF };
