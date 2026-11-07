"use strict";

// A target's documents, as a consumer receives them.
//
// Whether a target changed is a question about what it publishes, not about which files
// git saw change (#121). A comment, a reformatted fragment, a part of a shared file the
// target never reaches, a fragment moved where the bundle does not show it: none of these
// changes the document, and none should ask for a new version. A placeholder snippet no
// $ref reaches does change it, and should.
//
// So the documents are built -- from the working tree, or from a ref -- in a copy of the
// library made for the purpose, never in the library's own build directories, and
// compared as canonical JSON without the version stamped on them.

const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFileSync } = require("child_process");
const { gitEnv } = require("./git-env");
const YAML = require("yaml");

const { load } = require("./config");
const toolchain = require("./toolchain");

/** RFC 8785-style canonical JSON: keys sorted at every level, lists in their order. */
function canonical(value) {
    if (value === undefined) return "undefined";
    if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
    if (value !== null && typeof value === "object") {
        return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${canonical(value[k])}`).join(",")}}`;
    }
    return JSON.stringify(value);
}

/** A built document's content, without the version stamped on it. */
function documentOf(text) {
    const document = YAML.parse(text);
    if (document && document.info && typeof document.info === "object") delete document.info.version;
    return document;
}

/** Whether two targets' documents, by kind, are the same. */
function sameDocuments(a, b) {
    return canonical(a) === canonical(b);
}

function git(args, cwd, options = {}) {
    return execFileSync("git", args, { cwd, stdio: ["ignore", "pipe", "pipe"], maxBuffer: 1 << 30, env: gitEnv(), ...options });
}

/** Copy the library as the working tree has it: tracked and untracked files git does not ignore. */
function exportWorkingTree(repoRoot, libRel, into) {
    const listed = git(["ls-files", "-z", "-c", "-o", "--exclude-standard", "--", libRel || "."], repoRoot)
        .toString("utf8").split("\0").filter(Boolean);
    for (const rel of [...new Set(listed)]) {
        const from = path.join(repoRoot, rel);
        let stat;
        try {
            stat = fs.lstatSync(from);
        } catch {
            continue; // tracked, but deleted in the working tree
        }
        const to = path.join(into, rel);
        fs.mkdirSync(path.dirname(to), { recursive: true });
        if (stat.isSymbolicLink()) fs.symlinkSync(fs.readlinkSync(from), to);
        else if (stat.isFile()) fs.copyFileSync(from, to);
    }
}

/** Copy the library as `ref` has it. */
function exportRef(repoRoot, ref, libRel, into) {
    const archive = git(["archive", "--format=tar", ref, "--", libRel || "."], repoRoot);
    execFileSync("tar", ["-x", "-f", "-", "-C", into], { input: archive, stdio: ["pipe", "ignore", "pipe"] });
}

/**
 * Drop the `versioning` section from a baseline's copy of apionly.yaml.
 *
 * It does not shape a document: it only tells the guard where to look, and the guard
 * reads it from today's configuration. A baseline from before a key in it was renamed
 * -- `mainBranch`, `baseBranch` since 0.18.5 -- could otherwise not be read at all, and
 * every target would be left undecided (#123).
 */
function withoutVersioning(file) {
    const document = YAML.parseDocument(fs.readFileSync(file, "utf8"));
    if (!YAML.isMap(document.contents) || !document.contents.has("versioning")) return;
    document.contents.delete("versioning");
    fs.writeFileSync(file, document.toString());
}

/**
 * Pin each tool of the copy's configuration to what this installation can run: its own
 * pin when that can be had, otherwise the one `current` pins. A baseline released with an
 * older bundler than the sealed image carries is still compared, and the reason says how.
 */
function usableToolchain(copy, current, kinds) {
    const notes = [];
    for (const kind of kinds) {
        if (copy.targetsFor(kind).length === 0) continue;
        const key = toolchain.TOOL_OF_KIND[kind];
        const own = copy.toolchain && copy.toolchain[key];
        try {
            toolchain.resolve(key, copy.tool(key));
        } catch (error) {
            const today = current.tool(key);
            if (!own || own === today) throw error;
            toolchain.resolve(key, today);
            copy.toolchain[key] = today;
            notes.push(`built with ${today}, as ${own} cannot be had`);
        }
    }
    return notes;
}

/**
 * Each target's documents, by kind, built from `ref` -- or from the working tree when
 * `ref` is null -- in a copy of the library.
 *
 * A target that does not build is reported with its error rather than failing the rest;
 * so is every target when the copy's configuration cannot be read.
 *
 * @param {object} config the library's loaded configuration
 * @param {string[]} targets
 * @param {{ref?: string|null, repoRoot: string}} options
 * @returns {{documents: Map<string, {documents?: object, error?: Error}>, notes: string[]}}
 */
function targetDocuments(config, targets, { ref = null, repoRoot }) {
    // Required here, not at the top: the pipeline requires modules that require this one.
    const { build } = require("./pipeline");
    const libRel = path.relative(repoRoot, fs.realpathSync(config.root)).split(path.sep).join("/");
    const configRel = path.relative(fs.realpathSync(config.root), fs.realpathSync(config.path));
    const scratch = fs.mkdtempSync(path.join(os.tmpdir(), "aop-documents-"));
    const documents = new Map();
    let notes = [];
    try {
        let copy;
        try {
            if (ref === null) exportWorkingTree(repoRoot, libRel, scratch);
            else exportRef(repoRoot, ref, libRel, scratch);
            const configFile = path.join(scratch, libRel, configRel);
            if (ref !== null) withoutVersioning(configFile);
            copy = load(configFile);
            notes = usableToolchain(copy, config, ["openapi", "asyncapi"]);
        } catch (error) {
            for (const target of targets) documents.set(target, { error });
            return { documents, notes };
        }
        for (const target of targets) {
            if (!copy.targets[target]) {
                documents.set(target, { documents: {} });
                continue;
            }
            try {
                const built = build(copy, { targets: [target], verify: false, distribute: false });
                const byKind = {};
                for (const result of built) byKind[result.kind] = documentOf(fs.readFileSync(result.file, "utf8"));
                documents.set(target, { documents: byKind });
            } catch (error) {
                documents.set(target, { error });
            }
        }
        return { documents, notes };
    } finally {
        fs.rmSync(scratch, { recursive: true, force: true });
    }
}

module.exports = { canonical, documentOf, sameDocuments, targetDocuments };
