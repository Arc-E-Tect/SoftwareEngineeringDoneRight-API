"use strict";

// A changed specification comes with a changed version.
//
// A subscriber locks a version's documents by their hashes, so a version published again
// with other documents breaks every build that already locked it. The registries refuse
// such a version, but only at publish time, only on remote channels, and only when they
// already hold it; this guard decides it from git alone, before anything is packed, so a
// pull request can fail on it.
//
// What a version was released from is known from the target's release tags, which the
// pipeline creates after it publishes (`versioning.tagFormat`); the Publisher only reads
// them. A target with no release tag yet is compared with the merge-base with the main
// branch, or with the ref `--since` names.

const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const { prepare } = require("./pipeline");
const { changedSince } = require("./changed");
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

/** The ref a target without release tags is compared with, and how to name it. */
function fallbackBaseline(config, repoRoot, since) {
    if (since) {
        const commit = tryGit(["rev-parse", "--verify", "--quiet", `${since}^{commit}`], repoRoot);
        if (!commit) throw new GuardError(`--since ${since} is not a commit in this repository`);
        return { ref: commit, label: `${since} (${commit.slice(0, 12)})` };
    }
    const branch = config.mainBranch();
    for (const candidate of [`origin/${branch}`, branch]) {
        if (!tryGit(["rev-parse", "--verify", "--quiet", `${candidate}^{commit}`], repoRoot)) continue;
        const base = tryGit(["merge-base", "HEAD", candidate], repoRoot);
        if (base) return { ref: base, label: `the merge-base with ${branch} (${base.slice(0, 12)})` };
    }
    throw new GuardError(
        `no merge-base with ${branch}, the branch versioning.mainBranch names, to compare targets without a release tag with. ` +
        "In CI, fetch the full history and the branch (actions/checkout with fetch-depth: 0, or GIT_DEPTH: 0 on GitLab), " +
        "or name the ref to compare with: --since <ref>.");
}

/** The version a target's version file -- `rel`, from the repository root -- declared at `ref`, or null when it was not there. */
function versionAt(ref, rel, repoRoot) {
    const text = tryGit(["show", `${ref}:${rel}`], repoRoot);
    return text === null ? null : parseProperties(text).version || null;
}

/**
 * Whether each target's version may be published: a version released before, from other
 * specifications, may not; nor one lower than what came before it.
 *
 * @param {object} config a loaded configuration
 * @param {string[]} targets the targets to judge; each needs a version file
 * @param {{preRelease?: string|null, since?: string|null}} options
 * @returns {Array<{target: string, version: string, verdict: "ok"|"violation", reason: string}>}
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
    prepare(config);

    const changes = new Map();
    const changedSinceRef = (ref, target) => {
        if (!changes.has(ref)) {
            changes.set(ref, new Map(changedSince(config, ref).map((r) => [r.target, r.changed])));
        }
        return changes.get(ref).get(target) === true;
    };
    let fallback = null;

    return targets.map((target) => {
        const declared = versionOf(config, target);
        const version = versionOf(config, target, { preRelease });
        const file = path.relative(repoRoot, fs.realpathSync(config.versionFile(target))).split(path.sep).join("/");
        const ok = (reason) => ({ target, version, verdict: "ok", reason });
        const violation = (reason) => ({ target, version, verdict: "violation", reason });
        const tags = releaseTags(config, target, repoRoot);

        // Released before: exact, whatever else is true.
        if (tags.has(version)) {
            const tag = tags.get(version);
            return changedSinceRef(tag, target)
                ? violation(`${version} was released as ${tag}, and ${target} has changed since: raise the version in ${file}`)
                : ok(`unchanged since its release tag ${tag}`);
        }
        if (preRelease && tags.has(declared)) {
            return violation(`${version} is a pre-release of ${declared}, which is already released as ${tags.get(declared)}: ` +
                `it would sort before the release; declare the next version in ${file}`);
        }
        const releases = [...tags.keys()].filter((v) => !isPrerelease(v)).sort(compare);
        if (releases.length > 0) {
            const latest = releases[releases.length - 1];
            return compare(declared, latest) < 0
                ? violation(`${declared} is lower than its latest release, ${latest} (${tags.get(latest)}): raise the version in ${file}`)
                : ok(`${declared} is above its latest release, ${latest}`);
        }

        // Never released, as far as the tags tell: the merge-base, or --since, decides.
        fallback = fallback || fallbackBaseline(config, repoRoot, since);
        const before = versionAt(fallback.ref, file, repoRoot);
        if (before === null) return ok(`new: no version file at ${fallback.label}`);
        if (compare(declared, before) < 0) {
            return violation(`${declared} is lower than ${before} at ${fallback.label}: raise the version in ${file}`);
        }
        if (!changedSinceRef(fallback.ref, target)) return ok(`unchanged since ${fallback.label}`);
        if (compare(declared, before) > 0) return ok(`${before} -> ${declared} since ${fallback.label}`);
        if (preRelease) return ok(`pre-release ${version}: its identifiers make it a new version`);
        return violation(`${target} changed since ${fallback.label}, but its version is still ${declared}: ` +
            `raise it in ${file}`);
    });
}

module.exports = { guard, releaseTags, GuardError };
