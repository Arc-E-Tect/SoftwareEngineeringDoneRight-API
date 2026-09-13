"use strict";

// Packing a built target into a distributable archive plus its manifest.
//
// The manifest is what makes provenance auditable, and it is the only thing the
// Publisher and the Subscriber agree on. Treat its shape as expensive to change.

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { execFileSync } = require("child_process");
const YAML = require("yaml");

const MANIFEST_NAME = "manifest.json";
const MANIFEST_SCHEMA_VERSION = 1;

class PackError extends Error {}

function sha256(file) {
    return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function gitInfo(cwd) {
    const git = (args) => {
        try {
            return execFileSync("git", args, { cwd, encoding: "utf8" }).trim();
        } catch {
            return null;
        }
    };
    return {
        repository: git(["config", "--get", "remote.origin.url"]),
        commit: git(["rev-parse", "HEAD"]),
    };
}

/**
 * Build the manifest for one target.
 *
 * Computed once, before any channel fan-out, so that every channel ships the
 * same bytes. Building it per channel invites the npm copy and the Maven copy of
 * one version to differ by a line ending, which surfaces months later as an
 * unexplainable verify failure.
 */
function manifest(config, target, { version, files, closureSha256, producedAt = new Date() }) {
    if (!version) throw new PackError(`target '${target}': pack requires a version`);

    const source = gitInfo(config.root);
    return {
        schemaVersion: MANIFEST_SCHEMA_VERSION,
        target,
        version,
        producedAt: producedAt.toISOString().replace(/\.\d{3}Z$/, "Z"),
        closureSha256,
        source,
        files: files
            .map((file) => ({ path: path.basename(file), sha256: sha256(file) }))
            .sort((a, b) => (a.path < b.path ? -1 : 1)),
    };
}

/**
 * Write the manifest into a target's dist directory and archive the whole thing.
 *
 * @returns {{archive: string, manifest: object, manifestPath: string}}
 */
function pack(config, target, { version, closureSha256, outDir, log = () => {} }) {
    const distDir = config.distDir(target);
    if (!fs.existsSync(distDir)) {
        throw new PackError(`target '${target}': nothing built at ${distDir}; run 'build' first`);
    }

    const documents = fs
        .readdirSync(distDir)
        .filter((name) => /\.ya?ml$/.test(name))
        .map((name) => path.join(distDir, name))
        .sort();
    if (documents.length === 0) {
        throw new PackError(`target '${target}': no documents in ${distDir}`);
    }

    // A manifest that claims a version the documents were not stamped with is
    // worse than no manifest: every downstream check would agree with it, and the
    // mismatch would only surface as a consumer reading a contract whose
    // info.version is not the version they asked for. Nothing else in the
    // pipeline catches this, because `pack` is the first step that takes the
    // version on trust from its caller rather than deriving it.
    for (const document of documents) {
        let declared;
        try {
            const parsed = YAML.parse(fs.readFileSync(document, "utf8"));
            declared = parsed && parsed.info ? String(parsed.info.version) : null;
        } catch (error) {
            throw new PackError(`${document} is not valid YAML: ${error.message}`);
        }
        if (declared !== String(version)) {
            throw new PackError(
                `target '${target}': ${path.basename(document)} declares info.version '${declared}' ` +
                `but is being packed as '${version}'. Build it again first: its version file, or the ` +
                `--pre-release it was built with, has changed since.`
            );
        }
    }

    const data = manifest(config, target, { version, files: documents, closureSha256 });
    const manifestPath = path.join(distDir, MANIFEST_NAME);
    fs.writeFileSync(manifestPath, JSON.stringify(data, null, 2) + "\n");

    fs.mkdirSync(outDir, { recursive: true });
    const archive = path.join(outDir, `${target}-${version}.tgz`);

    // Deterministic archive: sorted entry names, and no mtime/owner noise, so the
    // same inputs produce the same bytes on any machine.
    const entries = documents.map((d) => path.basename(d)).concat([MANIFEST_NAME]).sort();
    execFileSync("tar", [
        "-czf", archive,
        "-C", distDir,
        "--numeric-owner",
        ...entries,
    ], { encoding: "utf8" });

    log(`-- Packed ${path.basename(archive)} (${data.files.length} document(s))`);
    return { archive, manifest: data, manifestPath };
}

module.exports = { pack, manifest, sha256, MANIFEST_NAME, MANIFEST_SCHEMA_VERSION, PackError };
