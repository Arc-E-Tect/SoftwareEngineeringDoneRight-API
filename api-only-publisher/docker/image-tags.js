#!/usr/bin/env node
"use strict";

// The Docker image's tag rules, which the release workflow applies. README.adoc#docker-tags.
//
// An exact-version tag (1.4.2) is written once and never points at another
// digest, because vetted mirrors depend on it: when GHCR already has the version,
// its digest is reused rather than rebuilt, and Docker Hub is given that same
// digest or left alone. The floating tags (1.4, 1, latest) move only for the
// highest released version, never for a re-publish of an older one and never for
// a pre-release.
//
//   image-tags.js plan --version <v> --released "<versions>" --ghcr-digest <digest|"">
//   image-tags.js promotion --version <v> --source-digest <digest> --existing-digest <digest|"">
//
// Each prints key=value lines for $GITHUB_OUTPUT.

const { parse, isPrerelease, VersionError } = require("../src/version-policy");

class ImageTagError extends Error {}

const DIGEST = /^sha256:[0-9a-f]{64}$/;

function semver(version) {
    try {
        return parse(version);
    } catch (error) {
        if (error instanceof VersionError) throw new ImageTagError(error.message);
        throw error;
    }
}

function digest(value, name) {
    if (!value) return null;
    if (!DIGEST.test(value)) throw new ImageTagError(`${name} must be a sha256 digest, not '${value}'`);
    return value;
}

function compare(a, b) {
    return a.major - b.major || a.minor - b.minor || a.patch - b.patch;
}

/**
 * What to do for `version`, given every released version and the digest GHCR has
 * for it, if any: whether to build, and which floating tags to move.
 */
function plan({ version, released, ghcrDigest = null }) {
    const own = semver(version);
    const stable = released.map((v) => ({ v, parsed: semver(v) })).filter(({ v }) => !isPrerelease(v));
    const highest = !isPrerelease(version) && stable.every(({ parsed }) => compare(parsed, own) <= 0);
    const existing = digest(ghcrDigest, "the GHCR digest");
    return {
        version,
        build: existing === null,
        digest: existing,
        floating: highest ? [`${own.major}.${own.minor}`, `${own.major}`, "latest"] : [],
    };
}

/**
 * Whether Docker Hub's exact-version tag gets `sourceDigest`: `copy` when it has
 * none, `present` when it already has that digest, and never another digest.
 */
function promotion({ version, sourceDigest, existingDigest = null }) {
    const source = digest(sourceDigest, "the source digest");
    const existing = digest(existingDigest, "the existing digest");
    if (existing === null) return "copy";
    if (existing === source) return "present";
    throw new ImageTagError(
        `${version} already points at ${existing} on Docker Hub, and ${source} is not that image. ` +
        "An exact-version tag is never re-pointed: vetted mirrors rely on it.");
}

function main(argv) {
    const [command, ...rest] = argv;
    const options = {};
    for (let i = 0; i < rest.length; i += 2) {
        if (!rest[i].startsWith("--") || i + 1 >= rest.length) throw new ImageTagError(`usage: bad argument '${rest[i]}'`);
        options[rest[i].slice(2)] = rest[i + 1];
    }
    if (command === "plan") {
        const result = plan({
            version: options.version,
            released: (options.released || "").split(/\s+/).filter(Boolean),
            ghcrDigest: options["ghcr-digest"],
        });
        return `build=${result.build}\ndigest=${result.digest || ""}\nfloating=${result.floating.join(" ")}\n`;
    }
    if (command === "promotion") {
        const action = promotion({
            version: options.version, sourceDigest: options["source-digest"], existingDigest: options["existing-digest"],
        });
        return `action=${action}\n`;
    }
    throw new ImageTagError("usage: image-tags.js plan|promotion --option value ...");
}

if (require.main === module) {
    try {
        process.stdout.write(main(process.argv.slice(2)));
    } catch (error) {
        if (!(error instanceof ImageTagError)) throw error;
        process.stderr.write(`image-tags: ${error.message}\n`);
        process.exit(1);
    }
}

module.exports = { plan, promotion, main, ImageTagError };
