"use strict";

// A target's version.
//
// It is read from a version file kept with the target's specification, rather
// than passed in by whatever runs the build. A version given on the command line
// lets two builds of one commit publish different versions, and lets through a
// version nobody reviewed. A version in the library changes in the same commit as
// the fragments it describes, and is reviewed with them.
//
// The file holds a release version. A pre-release is that version with
// identifiers appended when it is built, so cutting one never means editing the
// file -- and then remembering to edit it back.

const fs = require("fs");

const { parse, isPrerelease } = require("./version-policy");

class BundleVersionError extends Error {}

// Java-properties style -- `key=value` or `key: value`, with `#` and `!` comments
// -- so a JVM build can read the same file with java.util.Properties. Only what a
// version file needs: no escapes, no continuation lines.
function readProperties(file) {
    const properties = {};
    for (const raw of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
        const line = raw.trim();
        if (line === "" || line.startsWith("#") || line.startsWith("!")) continue;
        const separator = line.search(/[=:]/);
        if (separator < 0) continue;
        properties[line.slice(0, separator).trim()] = line.slice(separator + 1).trim();
    }
    return properties;
}

/**
 * The version `target` is built, packed and published as.
 *
 * @param {object} config a loaded configuration
 * @param {string} target the target whose version to read
 * @param {{preRelease?: string|null}} options pre-release identifiers to append, such as `rc.1`
 * @returns {string}
 */
function versionOf(config, target, { preRelease = null } = {}) {
    const file = config.versionFile(target);
    if (!fs.existsSync(file)) {
        throw new BundleVersionError(
            `target '${target}': no version file at ${file}. Create it with a line such as 'version=1.0.0', ` +
            `or point targets.${target}.versionFile at the file that holds its version.`
        );
    }
    const declared = readProperties(file).version;
    if (!declared) {
        throw new BundleVersionError(`target '${target}': ${file} declares no version`);
    }
    try {
        parse(declared);
    } catch (error) {
        throw new BundleVersionError(`target '${target}': ${file}: ${error.message}`);
    }
    if (isPrerelease(declared)) {
        throw new BundleVersionError(
            `target '${target}': ${file} declares '${declared}', but a version file holds a release version. ` +
            `Declare the release it leads to, and cut the pre-release with --pre-release <identifiers>.`
        );
    }
    if (!preRelease) return declared;

    const version = `${declared}-${preRelease}`;
    try {
        parse(version);
    } catch (error) {
        throw new BundleVersionError(
            `--pre-release '${preRelease}' does not make a semantic version of ${declared}: ${error.message}`
        );
    }
    return version;
}

module.exports = { versionOf, readProperties, BundleVersionError };
