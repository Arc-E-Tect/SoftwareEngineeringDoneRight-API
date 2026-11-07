"use strict";

// Which targets a commit actually changed.
//
// Under repository-wide versioning this question does not arise, because
// everything bumps together. Under per-target versioning the release job has to
// answer it, or a change to one service's context releases every other service
// too, and the version numbers stop meaning anything.
//
// A target changed when its documents did (#121): it is built as `since` has it and
// as the working tree has it, and the two are compared, as the version guard does.
// The files git saw change in its closure are reported alongside, for the reader.

const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");
const { gitEnv } = require("./git-env");

const { forTargets } = require("./closure");
const { targetDocuments, sameDocuments } = require("./documents");

class ChangedError extends Error {}

function git(args, cwd) {
    try {
        return execFileSync("git", args, { cwd, encoding: "utf8", env: gitEnv() });
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
 * @returns {Array<{target, changed: boolean, files: string[], closureSha256: string}>} `changed` when the
 *     target's documents differ; `files`, the files in its closure git saw change, whether they did or not
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
    const targets = [...closures.keys()];
    const now = targetDocuments(config, targets, { repoRoot }).documents;
    const then = targetDocuments(config, targets, { ref: since, repoRoot }).documents;
    const results = [];

    for (const [target, entry] of closures) {
        const sources = entry.files
            .map((f) => toSource(config, f, kinds))
            .filter(Boolean)
            .map(real);
        const touched = [...new Set(sources.filter((f) => diff.has(f)))].sort();
        const mine = now.get(target);
        if (mine.error) throw new ChangedError(`${target} could not be built from the working tree: ${mine.error.message}`);
        const theirs = then.get(target);
        // A target that does not build as `since` has it cannot be shown unchanged.
        if (theirs.error) log(`${target}: could not be built at ${since}, so counted as changed: ${theirs.error.message}`);
        const changed = Boolean(theirs.error) || !sameDocuments(theirs.documents, mine.documents);
        results.push({
            target,
            changed,
            files: touched.map((f) => path.relative(repoRoot, f)),
            closureSha256: entry.sha256,
        });
        log(`${target.padEnd(18)} ${changed ? "changed" : "unchanged"}  ${entry.sha256.slice(0, 12)}`);
    }
    return results;
}

module.exports = { changedSince, toSource, ChangedError };
