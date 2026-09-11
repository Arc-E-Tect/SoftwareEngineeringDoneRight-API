"use strict";

// Setting info.version on a bundled document.
//
// This replaces two different sed expressions, which between them differed per
// specification type (OpenAPI quoted the version, AsyncAPI did not), did nothing
// at all when the pattern missed, and could match a `version:` field elsewhere in
// the document.
//
// The field is located structurally and only that one scalar is spliced. It is
// deliberately not a re-serialisation: re-emitting the document reformats
// everything around the edit, because the AsyncAPI CLI wraps long descriptions at
// a width no YAML emitter reproduces. Splicing keeps every other byte exactly as
// the bundler wrote it.

const fs = require("fs");
const YAML = require("yaml");

class VersionError extends Error {}

/**
 * @returns {string} the document with info.version set to `version`
 */
function stamp(source, version, describeAs = "document") {
    let doc;
    try {
        doc = YAML.parseDocument(source);
    } catch (error) {
        throw new VersionError(`${describeAs} is not valid YAML: ${error.message}`);
    }
    if (doc.errors && doc.errors.length > 0) {
        throw new VersionError(`${describeAs} is not valid YAML: ${doc.errors[0].message}`);
    }

    if (!doc.get("info", true)) {
        throw new VersionError(`${describeAs} has no top-level 'info' block to stamp a version into`);
    }

    const node = doc.getIn(["info", "version"], true);
    if (!node || !Array.isArray(node.range)) {
        throw new VersionError(`${describeAs} has an 'info' block with no 'version' key`);
    }

    const [start, valueEnd] = node.range;
    const original = source.slice(start, valueEnd);
    // Keep whatever quoting the bundler chose, so stamping changes the version
    // and nothing else about the line.
    const quote = /^['"]/.test(original) ? original[0] : "";
    return source.slice(0, start) + quote + version + quote + source.slice(valueEnd);
}

function stampFile(file, version) {
    const before = fs.readFileSync(file, "utf8");
    const after = stamp(before, version, file);

    // Prove the edit landed and left the document parseable, rather than
    // trusting a substitution to have done what it looked like it did.
    const check = YAML.parse(after);
    if (!check || !check.info || String(check.info.version) !== String(version)) {
        throw new VersionError(
            `${file} still reports info.version ` +
            `'${check && check.info ? check.info.version : "<none>"}' after stamping '${version}'`
        );
    }
    fs.writeFileSync(file, after);
    return after;
}

module.exports = { stamp, stampFile, VersionError };
