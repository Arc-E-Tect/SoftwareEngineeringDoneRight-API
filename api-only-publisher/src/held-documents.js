"use strict";

// What a destination already holds under a version, told apart by its documents.
//
// A published version is its documents: the manifest's `files`, each document's name and
// SHA-256, which is exactly what a subscriber records in its lockfile. Comparing bytes
// instead says too much: the manifest also records the commit it was packed from and that
// commit's time, so the same documents packed from a later commit are another archive,
// and a channel comparing bytes would refuse a version that has not changed.

const { untgz } = require("./tar");
const { unzip } = require("./zip");

class HeldError extends Error {}

// Where each kind of package keeps the manifest: the packed archive at its root, an npm
// package below package/, a NuGet package in its content folder.
const MANIFEST_ENTRIES = ["manifest.json", "package/manifest.json", "contracts/manifest.json"];

function kindOf(bytes) {
    if (bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b) return "tgz";
    if (bytes.length >= 4 && bytes.readUInt32LE(0) === 0x04034b50) return "zip";
    if (/^\s*\{/.test(bytes.toString("utf8", 0, 64))) return "json";
    throw new HeldError("it is neither a manifest nor a package");
}

/**
 * The manifest a destination holds, from the manifest itself or from the package that
 * carries it: its parsed contents and its bytes, as written.
 *
 * @param {Buffer} bytes a manifest.json, a packed archive, an npm package or a .nupkg
 * @param {"tgz"|"zip"|"json"} [kind] what `bytes` should be; recognised when omitted
 * @returns {{manifest: object, bytes: Buffer}}
 */
function heldManifest(bytes, kind = kindOf(bytes)) {
    let raw = bytes;
    if (kind !== "json") {
        let entries;
        try {
            entries = kind === "zip" ? unzip(bytes) : untgz(bytes);
        } catch (error) {
            // A truncated package can also end a read out of bounds; it is unreadable either way.
            throw new HeldError(error.message);
        }
        const entry = entries.find((e) => MANIFEST_ENTRIES.includes(e.name));
        if (!entry) throw new HeldError("it holds no manifest.json");
        raw = entry.data;
    }
    let manifest;
    try {
        manifest = JSON.parse(raw.toString("utf8"));
    } catch (error) {
        throw new HeldError(`its manifest.json is not JSON: ${error.message}`);
    }
    if (!manifest || !Array.isArray(manifest.files)) throw new HeldError("its manifest.json lists no files");
    return { manifest, bytes: raw };
}

/** The manifest in `bytes`; see heldManifest. */
function manifestIn(bytes, kind) {
    return heldManifest(bytes, kind).manifest;
}

/**
 * Whether `held` describes the same documents as `mine`: the same target and version, and
 * the same document names with the same hashes, in any order. Provenance is not compared.
 */
function sameDocuments(held, mine) {
    if (held.target !== mine.target || String(held.version) !== String(mine.version)) return false;
    const listing = (manifest) => manifest.files.map((f) => `${f.path}\0${f.sha256}`).sort().join("\n");
    return listing(held) === listing(mine);
}

/**
 * What a destination's copy of a version is, against the manifest being published:
 * `same` with the manifest it carries, `different`, or `unreadable` with the reason --
 * something that cannot be compared is never taken for the same version.
 *
 * @returns {{verdict: "same", held: {manifest: object, bytes: Buffer}} | {verdict: "different"} | {verdict: "unreadable", reason: string}}
 */
function judgeHeld(bytes, kind, manifest) {
    let held;
    try {
        held = heldManifest(bytes, kind);
    } catch (error) {
        return { verdict: "unreadable", reason: error.message };
    }
    return sameDocuments(held.manifest, manifest) ? { verdict: "same", held } : { verdict: "different" };
}

module.exports = { heldManifest, manifestIn, sameDocuments, judgeHeld, HeldError };
