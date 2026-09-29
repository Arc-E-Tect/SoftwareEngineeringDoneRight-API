"use strict";

// The hand-off: what a publish run left on disk for a later step to ship.
//
// In the two-step model the Publisher -- typically in its Docker image -- packages
// bundles into files, and a following pipeline job publishes those files with the
// registry's own tools. That job must ship exactly the bytes the Publisher wrote,
// and nothing else, so every publish run lists each file a local channel wrote,
// with its SHA-256, in two forms: packages.json for a script to read, and
// packages.sha256 for `sha256sum -c`. Paths are relative to the library root, so
// the check runs from there.

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const HANDOFF_JSON = "packages.json";
const HANDOFF_SHA256 = "packages.sha256";
const HANDOFF_SCHEMA_VERSION = 1;

/**
 * Writes packages.json and packages.sha256 into `outDir`, replacing any from an
 * earlier run, listing `entries` -- `{ file, target, version, channel }`, `file`
 * absolute -- sorted by path.
 */
function writeHandoff(root, outDir, entries) {
    const files = entries
        .map(({ file, target, version, channel }) => ({
            path: path.relative(root, file).split(path.sep).join("/"),
            sha256: crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex"),
            target, version, channel,
        }))
        .sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));

    fs.mkdirSync(outDir, { recursive: true });
    const json = path.join(outDir, HANDOFF_JSON);
    const sha256 = path.join(outDir, HANDOFF_SHA256);
    fs.writeFileSync(json, JSON.stringify({ schemaVersion: HANDOFF_SCHEMA_VERSION, files }, null, 2) + "\n");
    fs.writeFileSync(sha256, files.map((f) => `${f.sha256}  ${f.path}\n`).join(""));
    return { json, sha256 };
}

module.exports = { writeHandoff, HANDOFF_JSON, HANDOFF_SHA256, HANDOFF_SCHEMA_VERSION };
