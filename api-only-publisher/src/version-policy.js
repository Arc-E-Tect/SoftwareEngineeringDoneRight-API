"use strict";

// What counts as a pre-release, and what that permits.
//
// API-Only design means implementation starts against a contract that is not
// finished. If the only way to obtain a bundle were a final release, teams would
// work around the tool by cloning the specification repository -- which is
// exactly the broad read access that publishing artifacts exists to avoid, with
// none of the guarantees.
//
// So pre-releases are first-class. The hard rule is the other half: a
// pre-release must never quietly satisfy a production build.

// SemVer, with the pre-release part being what follows a '-' before any '+'.
const SEMVER = /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?$/;

class VersionError extends Error {}

function parse(version) {
    const match = SEMVER.exec(String(version));
    if (!match) {
        throw new VersionError(
            `'${version}' is not a semantic version. Contracts are versioned with semver so that ` +
            `consumers can reason about what a bump means.`
        );
    }
    return {
        major: Number(match[1]),
        minor: Number(match[2]),
        patch: Number(match[3]),
        prerelease: match[4] || null,
        build: match[5] || null,
    };
}

/**
 * Semver precedence: negative when `a` comes before `b`, positive after, 0 when equal.
 * Build metadata is ignored; a release comes after its pre-releases; pre-release
 * identifiers compare numerically when both are numbers, a number before a word.
 */
function compare(a, b) {
    const x = parse(a);
    const y = parse(b);
    for (const part of ["major", "minor", "patch"]) {
        if (x[part] !== y[part]) return x[part] - y[part];
    }
    if (x.prerelease === y.prerelease) return 0;
    if (x.prerelease === null) return 1;
    if (y.prerelease === null) return -1;
    const xs = x.prerelease.split(".");
    const ys = y.prerelease.split(".");
    for (let i = 0; i < Math.min(xs.length, ys.length); i++) {
        if (xs[i] === ys[i]) continue;
        const [xn, yn] = [/^\d+$/.test(xs[i]), /^\d+$/.test(ys[i])];
        if (xn && yn) return Number(xs[i]) - Number(ys[i]);
        if (xn !== yn) return xn ? -1 : 1;
        return xs[i] < ys[i] ? -1 : 1;
    }
    return xs.length - ys.length;
}

function isPrerelease(version) {
    // -SNAPSHOT is not semver-legal as Maven spells it, but it is what Maven
    // consumers expect, so it is recognised rather than rejected.
    if (/-SNAPSHOT$/.test(String(version))) return true;
    return parse(version).prerelease !== null;
}

/**
 * The npm dist-tag a version should be published under.
 *
 * A pre-release goes to `next`, never `latest`, so that `npm install <pkg>`
 * cannot pick one up by accident. Both can be named otherwise -- `releaseTag` for a
 * patch to an older line that must not become `latest`, say.
 */
function npmDistTag(version, { releaseTag = "latest", prereleaseTag = "next" } = {}) {
    return isPrerelease(version) ? prereleaseTag : releaseTag;
}

/**
 * Whether a pre-release identifier is one we recognise, so that a typo in a
 * release job surfaces immediately instead of publishing something odd.
 */
function describe(version) {
    if (/-SNAPSHOT$/.test(String(version))) {
        return { prerelease: true, kind: "snapshot" };
    }
    const parsed = parse(version);
    if (!parsed.prerelease) return { prerelease: false, kind: "release" };
    if (/^rc\.\d+$/.test(parsed.prerelease)) return { prerelease: true, kind: "rc" };
    return { prerelease: true, kind: "other" };
}

module.exports = { parse, compare, isPrerelease, npmDistTag, describe, VersionError, SEMVER };
