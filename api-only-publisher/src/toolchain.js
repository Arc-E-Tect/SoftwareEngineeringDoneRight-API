"use strict";

// Finding the bundler and validator a build runs.
//
// Out of the box each tool runs through `npx --yes <pin>`, which downloads the
// pinned version when it is not cached. An installation that already carries the
// tools -- the Docker image -- names where they are, and a tool installed there at
// exactly the pinned version runs from its own bin instead. An installation that
// must not download anything at all says so, and a pin it does not carry then
// fails before anything runs, rather than reaching for the network.
//
// Both are environment variables, so the Publisher outside the image behaves as
// it always has: neither is set there.

const fs = require("fs");
const path = require("path");

const { BuildError } = require("./build-error");

/** Where the installed toolchain is: a directory `npm install --prefix` filled. */
const DIR_ENV = "API_ONLY_PUBLISHER_TOOLCHAIN_DIR";
/** `allow`, the default, falls back to npx; `never` refuses to. */
const DOWNLOAD_ENV = "API_ONLY_PUBLISHER_TOOLCHAIN_DOWNLOAD";
/** The image label naming the pin each tool is installed at, per toolchain key. */
const LABEL_PREFIX = "com.arc-e-tect.api-only-publisher.toolchain.";

/** Which toolchain key each kind of document is built with. */
const TOOL_OF_KIND = { openapi: "redocly", asyncapi: "asyncapi" };

class ToolchainError extends BuildError {}

/** A pin such as `@redocly/cli@2.52.0` as its package name and version; a bare name has none. */
function parseSpec(spec) {
    const at = spec.lastIndexOf("@");
    if (at <= 0) return { name: spec, version: null };
    return { name: spec.slice(0, at), version: spec.slice(at + 1) };
}

function readPackage(file) {
    try {
        return JSON.parse(fs.readFileSync(file, "utf8"));
    } catch {
        return null;
    }
}

function packageDir(dir, name) {
    return path.join(dir, "node_modules", ...name.split("/"));
}

/** Every tool the toolchain directory declares, as `name@installed-version`, in declaration order. */
function installed(dir) {
    if (!dir) return [];
    const manifest = readPackage(path.join(dir, "package.json"));
    if (!manifest) return [];
    return Object.keys(manifest.dependencies || {}).map((name) => {
        const own = readPackage(path.join(packageDir(dir, name), "package.json"));
        return `${name}@${own ? own.version : "(missing)"}`;
    });
}

// The bin to run: the only one, or the one named after the package or its scope --
// Redocly CLI declares `openapi` and `redocly`, and `redocly` is the tool.
function binOf(own, spec) {
    if (typeof own.bin === "string") return own.bin;
    const bins = Object.keys(own.bin || {});
    if (bins.length === 1) return own.bin[bins[0]];
    const scope = own.name.startsWith("@") ? own.name.slice(1).split("/")[0] : null;
    const unscoped = own.name.split("/").pop();
    const named = [unscoped, scope].find((candidate) => candidate && own.bin[candidate]);
    if (named) return own.bin[named];
    throw new ToolchainError(`${spec} declares several bins (${bins.join(", ")}), and none is named after it`);
}

function downloadMode(env) {
    const mode = env[DOWNLOAD_ENV] === undefined || env[DOWNLOAD_ENV] === "" ? "allow" : env[DOWNLOAD_ENV];
    if (mode !== "allow" && mode !== "never") {
        throw new ToolchainError(`${DOWNLOAD_ENV} must be allow or never, not ${JSON.stringify(mode)}`);
    }
    return mode;
}

function refusal(key, spec, dir, carried) {
    const { name } = parseSpec(spec);
    const same = carried.find((entry) => parseSpec(entry).name === name);
    const lines = [`apionly.yaml's toolchain.${key} pins ${spec}, and ${DOWNLOAD_ENV}=never forbids downloading it.`];
    if (carried.length === 0) {
        lines.push(`This installation carries no toolchain${dir ? ` in ${dir}` : `: ${DIR_ENV} is not set`}.`);
    } else {
        const list = carried.length === 1 ? carried[0] : `${carried.slice(0, -1).join(", ")} and ${carried[carried.length - 1]}`;
        lines.push(`This installation carries ${list}.`);
    }
    const align = same ? `Set toolchain.${key} to "${same}" in apionly.yaml, or use` : "Use";
    lines.push(`${align} the api-only-publisher image whose ${LABEL_PREFIX}${key} label is ${spec}.`);
    lines.push(`To allow the download instead, run with ${DOWNLOAD_ENV}=allow.`);
    return new ToolchainError(lines.join("\n"));
}

/**
 * How to run the tool pinned as `spec` under toolchain key `key`: a command and
 * the arguments that precede the tool's own.
 */
function resolve(key, spec, env = process.env) {
    const mode = downloadMode(env);
    const dir = env[DIR_ENV] || null;
    const { name, version } = parseSpec(spec);
    if (dir && version) {
        const root = packageDir(dir, name);
        const own = readPackage(path.join(root, "package.json"));
        if (own && own.version === version) return { command: path.join(root, binOf(own, spec)), args: [] };
    }
    if (mode === "never") throw refusal(key, spec, dir, installed(dir));
    return { command: "npx", args: ["--yes", spec] };
}

/** Resolves the tool of every kind in `kinds`, so a run that cannot have them fails before it starts. */
function requireTools(config, kinds, env = process.env) {
    for (const kind of kinds) resolve(TOOL_OF_KIND[kind], config.tool(TOOL_OF_KIND[kind]), env);
}

module.exports = {
    parseSpec, installed, resolve, requireTools, ToolchainError, DIR_ENV, DOWNLOAD_ENV, LABEL_PREFIX, TOOL_OF_KIND,
};
