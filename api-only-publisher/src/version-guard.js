"use strict";

// A changed specification comes with a changed version, and an unchanged one does not.
//
// A subscriber locks a version's documents by their hashes, so a version published again
// with other documents breaks every build that already locked it. The registries refuse
// such a version, but only at publish time, only on remote channels, and only when they
// already hold it; this guard decides it from git alone, before anything is packed, so a
// pull request can fail on it.
//
// "Changed" means the documents a consumer receives changed (#121): the target is built
// as it was released and as it is now, and the two are compared. An edit that does not
// reach the documents needs no new version, and a new version over the same documents is
// a warning, since it announces a change there is not. A refused target carries a hint
// at the version it needs.
//
// What a version was released from is known from the target's release tags, which the
// pipeline creates after it publishes (`versioning.tagFormat`); the Publisher only reads
// them. A target with no release tag yet is compared with the merge-base with the main
// branch, or with the ref `--since` names.

const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const { targetDocuments, sameDocuments } = require("./documents");
const { bumpHint } = require("./bump-hint");
const { versionOf, parseProperties } = require("./bundle-version");
const { compare, isPrerelease, SEMVER } = require("./version-policy");

class GuardError extends Error {}

function git(args, cwd) {
    return execFileSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
}

function tryGit(args, cwd) {
    try {
        return git(args, cwd);
    } catch {
        return null;
    }
}

function escape(text) {
    return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** The target's release tags, by version: every tag `tagFormat` gives for it with a semver version. */
function releaseTags(config, target, repoRoot) {
    const [before, after] = config.tagFormat().replace(/\{target\}/g, target).split("{version}");
    const pattern = new RegExp(`^${escape(before)}(.+)${escape(after)}$`);
    const tags = new Map();
    for (const tag of git(["tag", "--list"], repoRoot).split("\n")) {
        const match = pattern.exec(tag);
        if (match && SEMVER.test(match[1])) tags.set(match[1], tag);
    }
    return tags;
}

/**
 * The ref a target without release tags is compared with, and how to name it; or, when
 * there is no merge-base -- a shallow checkout, or no main branch -- why not.
 */
function fallbackBaseline(config, repoRoot, since) {
    if (since) {
        const commit = tryGit(["rev-parse", "--verify", "--quiet", `${since}^{commit}`], repoRoot);
        if (!commit) throw new GuardError(`--since ${since} is not a commit in this repository`);
        return { ref: commit, label: `${since} (${commit.slice(0, 12)})` };
    }
    const branch = config.baseBranch();
    for (const candidate of [`origin/${branch}`, branch]) {
        if (!tryGit(["rev-parse", "--verify", "--quiet", `${candidate}^{commit}`], repoRoot)) continue;
        const base = tryGit(["merge-base", "HEAD", candidate], repoRoot);
        if (base) return { ref: base, label: `the merge-base with ${branch} (${base.slice(0, 12)})` };
    }
    return { missing: `no merge-base with ${branch}, the branch versioning.baseBranch names, to compare a target without a release tag with. ` +
        "In CI, fetch the full history and the branch (actions/checkout with fetch-depth: 0, or GIT_DEPTH: 0 on GitLab), " +
        "or name the ref to compare with: --since <ref>." };
}

/** The version a target's version file -- `rel`, from the repository root -- declared at `ref`, or null when it was not there. */
function versionAt(ref, rel, repoRoot) {
    const text = tryGit(["show", `${ref}:${rel}`], repoRoot);
    return text === null ? null : parseProperties(text).version || null;
}

/**
 * Whether each target's version may be published: a version released before, from other
 * documents, may not; nor one lower than what came before it. A version raised over the
 * same documents may, with a warning.
 *
 * @param {object} config a loaded configuration
 * @param {string[]} targets the targets to judge; each needs a version file
 * @param {{preRelease?: string|null, since?: string|null}} options
 * A target the tags do not decide, with no merge-base to compare with, is `undecided`;
 * so is one whose documents cannot be built as they were at the baseline.
 *
 * @returns {Array<{target: string, version: string, verdict: "ok"|"warning"|"violation"|"undecided", reason: string,
 *     hint: {bump: string, version: string, why: string}|null}>}
 */
function guard(config, targets, { preRelease = null, since = null } = {}) {
    const top = tryGit(["rev-parse", "--show-toplevel"], config.root);
    if (top === null) {
        const error = new GuardError(`${config.root} is not in a git repository, so whether a changed target has a ` +
            "changed version cannot be decided: the guard reads release tags and history from git");
        error.notARepository = true;
        throw error;
    }
    // Real paths on both sides: git answers with the canonical one, and a checkout reached
    // through a symlink -- /var on macOS -- would otherwise never match.
    const repoRoot = fs.realpathSync(top);

    // Each tree is built once, for every target, and only when a verdict needs it.
    let current = null;
    const baselines = new Map();
    const builtAt = (ref) => {
        if (ref === null) return current || (current = targetDocuments(config, targets, { repoRoot }));
        if (!baselines.has(ref)) baselines.set(ref, targetDocuments(config, targets, { ref, repoRoot }));
        return baselines.get(ref);
    };
    /** The target's documents now against those at `ref`: {changed, hint, note} or, when `ref` does not build, {error}. */
    const compareWith = (ref, target, from) => {
        const mine = builtAt(null).documents.get(target);
        if (mine.error) throw new GuardError(`${target} could not be built from the working tree: ${mine.error.message}`);
        const baseline = builtAt(ref);
        const theirs = baseline.documents.get(target);
        if (theirs.error) return { error: theirs.error };
        const changed = !sameDocuments(theirs.documents, mine.documents);
        return {
            changed,
            hint: changed ? bumpHint(theirs.documents, mine.documents, from) : null,
            note: baseline.notes.length > 0 ? ` (${baseline.notes.join("; ")})` : "",
        };
    };
    let fallback = null;

    return targets.map((target) => {
        const declared = versionOf(config, target);
        const version = versionOf(config, target, { preRelease });
        const file = path.relative(repoRoot, fs.realpathSync(config.versionFile(target))).split(path.sep).join("/");
        const result = (verdict, reason, hint = null) => ({ target, version, verdict, reason, hint });
        const ok = (reason) => result("ok", reason);
        const warning = (reason) => result("warning", reason);
        const undecided = (reason) => result("undecided", reason);
        const violation = (reason, hint = null) => result("violation",
            hint ? `${reason}; hint: ${hint.version}, a ${hint.bump} bump: ${hint.why}` : reason, hint);
        const unbuildable = (label, error) => undecided(`${target} could not be built at ${label}: ${error.message}`);
        const tags = releaseTags(config, target, repoRoot);

        // Released before: exact, whatever else is true.
        if (tags.has(version)) {
            const tag = tags.get(version);
            const c = compareWith(tag, target, declared);
            if (c.error) return unbuildable(`its release tag ${tag}`, c.error);
            return c.changed
                ? violation(`${version} was released as ${tag}, and ${target} has changed since${c.note}: raise the version in ${file}`, c.hint)
                : ok(`unchanged since its release tag ${tag}${c.note}`);
        }
        if (preRelease && tags.has(declared)) {
            return violation(`${version} is a pre-release of ${declared}, which is already released as ${tags.get(declared)}: ` +
                `it would sort before the release; declare the next version in ${file}`);
        }
        const releases = [...tags.keys()].filter((v) => !isPrerelease(v)).sort(compare);
        if (releases.length > 0) {
            const latest = releases[releases.length - 1];
            const tag = tags.get(latest);
            if (compare(declared, latest) < 0) {
                return violation(`${declared} is lower than its latest release, ${latest} (${tag}): raise the version in ${file}`);
            }
            const c = compareWith(tag, target, latest);
            if (c.error) return ok(`${declared} is above its latest release, ${latest}; not compared with it: ${c.error.message}`);
            return c.changed
                ? ok(`${declared} is above its latest release, ${latest}${c.note}`)
                : warning(`${declared} is above its latest release, ${latest} (${tag}), but ${target}'s documents are ` +
                    `the same as that release's${c.note}: a new version announces a change there is not`);
        }

        // Never released, as far as the tags tell: the merge-base, or --since, decides.
        fallback = fallback || fallbackBaseline(config, repoRoot, since);
        if (fallback.missing) return undecided(fallback.missing);
        const before = versionAt(fallback.ref, file, repoRoot);
        if (before === null) return ok(`new: no version file at ${fallback.label}`);
        if (compare(declared, before) < 0) {
            return violation(`${declared} is lower than ${before} at ${fallback.label}: raise the version in ${file}`);
        }
        const c = compareWith(fallback.ref, target, declared);
        if (c.error) return unbuildable(fallback.label, c.error);
        if (!c.changed) {
            return compare(declared, before) > 0
                ? warning(`${declared} is above ${before} at ${fallback.label}, but ${target}'s documents are unchanged ` +
                    `since${c.note}: a new version announces a change there is not`)
                : ok(`unchanged since ${fallback.label}${c.note}`);
        }
        if (compare(declared, before) > 0) return ok(`${before} -> ${declared} since ${fallback.label}${c.note}`);
        if (preRelease) return ok(`pre-release ${version}: its identifiers make it a new version`);
        return violation(`${target} changed since ${fallback.label}${c.note}, but its version is still ${declared}: ` +
            `raise it in ${file}`, c.hint);
    });
}

module.exports = { guard, releaseTags, GuardError };
