"use strict";

// The pre-push hook `init` offers: `check` on the developer's own machine, before a
// target that changed without a new version leaves it. README.adoc#version-hook.
//
// Unlike the rest of the scaffold, where the hook goes and whether it is written at
// all depends on the repository the library is in, so this is where git is read:
//
// - The hook lives in .githooks/ at the top of the repository, committed, because
//   .git/hooks never is; core.hooksPath points git at it, once per clone.
// - A repository whose hooks a manager runs -- husky, lefthook, pre-commit, or a
//   core.hooksPath that points elsewhere -- gets no hook file, which would compete
//   with the manager, only the command to add to its pre-push entry.
// - The hook fetches on the host, never inside the Docker image: the image has no
//   ssh client and no credentials, and origin is what check compares with.

const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const HOOKS_DIR = ".githooks";
const IMAGE = "arcetect/api-only-publisher";

function tryGit(args, cwd) {
    try {
        return execFileSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
    } catch {
        return null;
    }
}

/** The closest directory at or above `dir` that exists: init may be scaffolding one that does not yet. */
function existingAncestor(dir) {
    let current = dir;
    while (!fs.existsSync(current)) current = path.dirname(current);
    return current;
}

/** A path as a single-quoted shell word. */
function shellQuote(text) {
    return /^[A-Za-z0-9_./-]+$/.test(text) ? text : `'${text.replace(/'/g, "'\\''")}'`;
}

/** The image tag the Docker variant runs: this Publisher's own version, or latest for a build that was never released. */
function imageTag(version) {
    return /^\d+\.\d+\.\d+$/.test(version) && version !== "0.0.0" ? version : "latest";
}

/**
 * The command a pre-push hook runs, from the top of the repository, for a library
 * `sub` below it ("" when the library is at the top).
 *
 * npx finds the Publisher in the library's own node_modules, so the npm variant
 * changes into the library first; the Docker variant mounts the whole repository,
 * .git included, and names the library with -C.
 */
function checkCommand(runner, sub, version) {
    if (runner === "docker") {
        return `docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/work" ${IMAGE}:${imageTag(version)} check` +
            (sub ? ` -C ${shellQuote(sub)}` : "");
    }
    return `${sub ? `cd ${shellQuote(sub)} && ` : ""}npx --no-install api-only-publisher check`;
}

const FETCH = `git fetch --quiet --tags origin || echo "pre-push: could not fetch origin; checking against what is here" >&2`;

/** The hook script itself. */
function hookScript(runner, sub, version) {
    const lines = [
        "#!/bin/sh",
        "# Written by api-only-publisher init: refuses the push when a published target",
        "# changed without a new version in its version file. `git push --no-verify` skips it.",
        "# check compares with origin's base branch and the release tags: bring them up to date.",
        FETCH,
    ];
    // A failed cd must stop the push, not run check somewhere else.
    if (runner === "npm" && sub) lines.push(`cd ${shellQuote(sub)} || exit 1`, `exec ${checkCommand(runner, "", version)}`);
    else lines.push(`exec ${checkCommand(runner, sub, version)}`);
    return lines.join("\n") + "\n";
}

/** The hook manager this repository already uses, by name, or null. */
function hookManager(top, hooksPath) {
    if (fs.existsSync(path.join(top, ".husky"))) return "husky";
    for (const file of ["lefthook.yml", "lefthook.yaml", ".lefthook.yml", ".lefthook.yaml"]) {
        if (fs.existsSync(path.join(top, file))) return "lefthook";
    }
    if (fs.existsSync(path.join(top, ".pre-commit-config.yaml"))) return "pre-commit";
    if (hooksPath && path.normalize(hooksPath).replace(/[\\/]+$/, "") !== HOOKS_DIR) return `core.hooksPath (${hooksPath})`;
    return null;
}

/**
 * What init does about the hook for a library in `dir`, run with `runner`.
 *
 * @returns {{
 *     files: Object<string, string>,   the hook file, keyed by its path relative to dir; empty under a manager
 *     executable: string[],            which of those files to make executable
 *     manager: string|null,            the hook manager found, if any
 *     command: string,                 what a manager's pre-push entry should run
 *     repository: string|null,         the top of the git repository, or null outside one
 *     hooksPath: "unset"|"set"|null,   whether init should set core.hooksPath; null when it has nothing to do
 * }}
 */
function planHook(dir, runner, version) {
    const top = tryGit(["rev-parse", "--show-toplevel"], existingAncestor(dir));
    const repository = top === null ? null : fs.realpathSync(top);
    // Real paths, as git answers with: a directory reached through a symlink -- /var on macOS -- would never match.
    const ancestor = existingAncestor(dir);
    const libraryDir = path.join(fs.realpathSync(ancestor), path.relative(ancestor, dir));
    // Outside a repository, the library is taken to become the top of one.
    const root = repository || libraryDir;
    const sub = path.relative(root, libraryDir).split(path.sep).join("/");
    const hooksPath = repository ? tryGit(["config", "--get", "core.hooksPath"], repository) : null;
    const manager = repository ? hookManager(repository, hooksPath) : null;
    const command = checkCommand(runner, sub, version);
    if (manager) return { files: {}, executable: [], manager, command, repository, hooksPath: null };

    const rel = path.relative(libraryDir, path.join(root, HOOKS_DIR, "pre-push")).split(path.sep).join("/");
    return {
        files: { [rel]: hookScript(runner, sub, version) },
        executable: [rel],
        manager: null,
        command,
        repository,
        hooksPath: hooksPath ? "set" : "unset",
    };
}

/** Points git at .githooks in this clone. */
function setHooksPath(repository) {
    execFileSync("git", ["config", "core.hooksPath", HOOKS_DIR], { cwd: repository, stdio: "ignore" });
}

module.exports = { planHook, setHooksPath, imageTag, HOOKS_DIR };
