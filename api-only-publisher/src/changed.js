"use strict";

// Which targets a commit actually changed.
//
// Under repository-wide versioning this question does not arise, because
// everything bumps together. Under per-target versioning the release job has to
// answer it, or a change to one service's context releases every other service
// too, and the version numbers stop meaning anything.

const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const { forTargets } = require("./closure");

class ChangedError extends Error {}

function git(args, cwd) {
    try {
        return execFileSync("git", args, { cwd, encoding: "utf8" });
    } catch (error) {
        throw new ChangedError(`git ${args.join(" ")} failed: ${(error.stderr || error.message).trim()}`);
    }
}

/**
 * Resolve symlinks before comparing paths.
 *
 * `git rev-parse --show-toplevel` answers with the canonical path, while the
 * configuration's paths are whatever the caller passed. On any checkout reached
 * through a symlink -- /var on macOS, a home directory behind one, a worktree
 * under a linked parent -- the two spellings never match, every intersection
 * comes out empty, and `changed` reports that nothing changed. For a command
 * whose answer decides what gets released, being silently wrong in the direction
 * of "release nothing" is the worst available failure.
 */
function real(file) {
    try {
        return fs.realpathSync(file);
    } catch {
        return path.resolve(file);
    }
}

/**
 * Map a staged file back to the source file it was copied from.
 *
 * Closures are computed over the staged tree, because a placeholder's content
 * genuinely changes the published document and only the staged copy has it
 * substituted. Git, however, only knows about the source.
 */
function toSource(config, stagedFile, kinds) {
    for (const kind of kinds) {
        const stagingRoot = config.stagingRoot(kind);
        const rel = path.relative(stagingRoot, stagedFile);
        if (!rel.startsWith("..") && !path.isAbsolute(rel)) {
            return path.join(config.sourceRoot(), rel);
        }
    }
    return null;
}

/**
 * @returns {Array<{target, changed: boolean, files: string[], closureSha256: string}>}
 */
function changedSince(config, since, { kinds = ["openapi", "asyncapi"], log = () => {} } = {}) {
    const repoRoot = real(git(["rev-parse", "--show-toplevel"], config.root).trim());

    // Everything git says differs between `since` and the working tree.
    const diff = new Set(
        git(["diff", "--name-only", since, "--"], repoRoot)
            .split("\n")
            .filter(Boolean)
            .map((rel) => real(path.resolve(repoRoot, rel)))
    );

    const closures = forTargets(config, kinds);
    const results = [];

    for (const [target, entry] of closures) {
        const sources = entry.files
            .map((f) => toSource(config, f, kinds))
            .filter(Boolean)
            .map(real);
        // A change to the *shape* of a closure -- a bundle gaining or losing a
        // $ref -- always means editing a file that is already in the closure, so
        // membership changes are caught without diffing membership itself.
        const touched = [...new Set(sources.filter((f) => diff.has(f)))].sort();
        results.push({
            target,
            changed: touched.length > 0,
            files: touched.map((f) => path.relative(repoRoot, f)),
            closureSha256: entry.sha256,
        });
        log(`${target.padEnd(18)} ${touched.length > 0 ? "changed" : "unchanged"}  ${entry.sha256.slice(0, 12)}`);
    }
    return results;
}

module.exports = { changedSince, toSource, ChangedError };
