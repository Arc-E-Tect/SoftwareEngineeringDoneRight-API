#!/usr/bin/env node
"use strict";

// Changes the toolchain the Publisher pins: README.adoc#toolchain-update.
//
// The toolchain is part of the Publisher, not a dependency of it: TOOLCHAIN_PIN makes
// init write its versions into a new apionly.yaml, outside Docker the Publisher fetches
// exactly those versions, the image installs them from docker/toolchain/ and its labels
// name them, and the README and the tests quote them. A library pinning another version
// fails with the image. So a new toolchain version is a feature release of its own,
// every one of those places changes in the same commit, and it is tested before it is
// committed:
//
//   scripts/toolchain-update.sh --redocly <version> [--asyncapi <version>]
//                               [--breaking "<what users must change>"] [--audit-fix]
//                               [--approve-scripts] [--skip-smoke] [--no-push]
//   scripts/toolchain-update.sh --audit-fix [--approve-scripts] [--skip-smoke] [--no-push]
//   scripts/toolchain-update.sh --help
//
// or `npm run toolchain:update -- <options>`.
//
// From a clean working tree, on a new branch from origin/main: the manifest and its
// lockfile, with npm audit fix applied to it given --audit-fix, then every tracked file
// quoting an old pin, then npm test and, unless --skip-smoke, the image built and
// smoke-tested as the build workflow does. A changed set of install scripts is refused,
// leaving nothing changed, until --approve-scripts approves it. Only then the commit: a
// feat for a new version (feat! with a BREAKING CHANGE footer, given --breaking), a fix
// for npm audit fixes alone. Unless --no-push, the branch is pushed and a pull request
// opened whose title is the commit's subject, so a squash merge releases what the commit
// says. Any other failure stops before the commit and leaves the branch for inspection.

const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const { parse, VersionError } = require("../src/version-policy");

class ToolchainUpdateError extends Error {}

/** The command-line option of each tool, the key `toolchain` pins it under in apionly.yaml. */
const TOOLS = ["redocly", "asyncapi"];

/** The kind of API description each tool serves, as TOOLCHAIN_PIN is keyed. */
const KIND_OF_TOOL = { redocly: "openapi", asyncapi: "asyncapi" };

/**
 * Tracked files that may quote a pin but are not changed with it: history, lockfiles
 * that npm writes, the manifest written as JSON, and the record of a vulnerability
 * review, which is redone against the new version rather than rewritten.
 */
const NOT_REWRITTEN = [/(^|\/)CHANGELOG\.md$/, /(^|\/)package-lock\.json$/, /^docker\/toolchain\/package\.json$/,
    /^\.trivyignore\.yaml$/];

const SMOKE_VERSION = "0.0.0-toolchain";
const SMOKE_IMAGE = "api-only-publisher:toolchain-update";

const USAGE = "usage: scripts/toolchain-update.sh [--redocly <version>] [--asyncapi <version>] " +
    "[--breaking \"<what users must change>\"] [--audit-fix] [--approve-scripts] [--skip-smoke] [--no-push]\n" +
    "Run it with --help for how to use it.";

const HELP = `Moves the Publisher's own toolchain pins: the Redocly CLI and AsyncAPI CLI versions
that init writes into a new apionly.yaml and the Docker image installs.

Usage, from any directory:

  api-only-publisher/scripts/toolchain-update.sh --redocly <version> [--asyncapi <version>] [options]
  api-only-publisher/scripts/toolchain-update.sh --asyncapi <version> [options]
  api-only-publisher/scripts/toolchain-update.sh --audit-fix [options]
  api-only-publisher/scripts/toolchain-update.sh --help

or, from api-only-publisher/, through npm, where the "--" is required, or npm takes
the options for itself:

  npm run toolchain:update -- <the same options>

What to change, one or more of:

  --redocly <version>    Pin @redocly/cli at <version>, a released version newer than
                         the current pin.
  --asyncapi <version>   Pin @asyncapi/cli at <version>, likewise.
  --audit-fix            Apply npm audit fix to the toolchain's lockfile. Alone, the
                         pins stay as they are and the commit is a fix; with a new
                         version, both go in one feature.

Options:

  --breaking "<text>"    The new version breaks libraries: commit it as feat!, with
                         <text>, what a library must change, as its BREAKING CHANGE
                         footer. Before 1.0.0 that releases a minor version, after it a
                         major one. Without it, a new version is a feat, with a warning
                         when it is a new major version of the tool.
  --approve-scripts      Approve the install scripts the toolchain now has. When the
                         set of packages with an install script changes, the script
                         lists them and stops, changing nothing; review each added
                         one, then run again with this option.
  --skip-smoke           Do not build and smoke-test the Docker image (no Docker on
                         this machine); the build workflow still does.
  --no-push              Commit, but do not push the branch or open a pull request.
  -h, --help             Show this help.

What it does, from a clean working tree:

  1. Creates a branch from origin/main: toolchain/redocly-<version>,
     toolchain/asyncapi-<version>, toolchain/audit-fix-<date>, or a combination.
  2. Changes docker/toolchain/package.json and regenerates its lockfile, then applies
     npm audit fix to it when asked.
  3. Checks the install scripts against allowScripts (see --approve-scripts).
  4. Replaces the old pin in every tracked file: TOOLCHAIN_PIN in src/init-config.js,
     the Dockerfile's labels, README.adoc and the tests. CHANGELOG.md, lockfiles and
     .trivyignore.yaml are left alone.
  5. Runs npm test, then builds the image and runs docker/smoke-test.sh.
  6. Commits: feat(api-only-publisher): ship <tool> <version> ..., or feat! with
     --breaking, or fix(api-only-publisher): apply npm audit fixes ... for --audit-fix
     alone. The message lists what a library must change, the lockfile changes,
     npm audit's findings before and after, the install scripts approved, and any
     .trivyignore.yaml review to redo.
  7. Pushes the branch and opens a pull request titled as the commit, so a squash
     merge releases what the commit says.

If a test or the smoke test fails, nothing is committed: the branch is left for you
to inspect. To give up on it: git switch - && git branch -D <branch>.

Examples:

  api-only-publisher/scripts/toolchain-update.sh --redocly 2.57.0
  api-only-publisher/scripts/toolchain-update.sh --asyncapi 7.0.0 --breaking "Re-pin toolchain.asyncapi; ..."
  api-only-publisher/scripts/toolchain-update.sh --audit-fix --approve-scripts --no-push

More: README.adoc, section "Moving the Publisher's own pins".
`;

function parseArgs(argv) {
    const options = { versions: {}, breaking: null, auditFix: false, approveScripts: false, smoke: true, push: true };
    if (argv.length === 0 || argv.includes("--help") || argv.includes("-h")) return { ...options, help: true };
    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i];
        const value = () => {
            if (i + 1 >= argv.length || argv[i + 1].startsWith("--")) throw new ToolchainUpdateError(`${arg} needs a value.\n${USAGE}`);
            return argv[++i];
        };
        if (arg === "--skip-smoke") options.smoke = false;
        else if (arg === "--no-push") options.push = false;
        else if (arg === "--audit-fix") options.auditFix = true;
        else if (arg === "--approve-scripts") options.approveScripts = true;
        else if (arg === "--breaking") options.breaking = value().trim();
        else if (TOOLS.includes(arg.slice(2)) && arg.startsWith("--")) options.versions[arg.slice(2)] = value();
        else throw new ToolchainUpdateError(`unknown argument '${arg}'.\n${USAGE}`);
    }
    const versions = Object.keys(options.versions).length > 0;
    if (!versions && !options.auditFix) throw new ToolchainUpdateError(`name a tool's new version, or --audit-fix.\n${USAGE}`);
    if (options.breaking === "") throw new ToolchainUpdateError("--breaking needs the change users must make");
    if (options.breaking && !versions) throw new ToolchainUpdateError("--breaking goes with a new version of a tool");
    return options;
}

function semver(version, name) {
    try {
        const parsed = parse(version);
        if (parsed.prerelease || parsed.build) throw new ToolchainUpdateError(`${name} ${version}: the image carries released versions only`);
        return parsed;
    } catch (error) {
        if (error instanceof VersionError) throw new ToolchainUpdateError(`${name}: ${error.message}`);
        throw error;
    }
}

function compare(a, b) {
    return a.major - b.major || a.minor - b.minor || a.patch - b.patch;
}

/** A pin such as `@redocly/cli@2.55.0` as its package name and version. */
function splitPin(pin) {
    const at = pin.lastIndexOf("@");
    return { name: pin.slice(0, at), version: pin.slice(at + 1) };
}

/**
 * What changes, from TOOLCHAIN_PIN and the versions asked for: one entry per tool,
 * each newer than what is pinned, and whether it is a new major version of the tool.
 */
function plan(pins, versions) {
    return TOOLS.filter((tool) => versions[tool]).map((tool) => {
        if (!pins[KIND_OF_TOOL[tool]]) throw new ToolchainUpdateError(`TOOLCHAIN_PIN pins nothing for ${tool}`);
        const { name, version: from } = splitPin(pins[KIND_OF_TOOL[tool]]);
        const to = versions[tool];
        const order = compare(semver(to, name), semver(from, name));
        if (order <= 0) throw new ToolchainUpdateError(`${name} ${to} is not newer than ${from}, the version the image carries`);
        return { tool, name, from, to, major: semver(to, name).major !== semver(from, name).major };
    });
}

/**
 * The end of a whole version: 2.55.0 is not the start of 2.55.01, 2.55.0-rc.1 or
 * 2.55.0.1, but a sentence may end with it.
 */
const WHOLE = "(?![\\w-]|\\.\\w)";

function escape(text) {
    return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** `text` with every pin in `changes` replaced, and how often each was. */
function rewrite(text, changes) {
    const counts = {};
    let result = text;
    for (const { name, from, to } of changes) {
        const pattern = new RegExp(`${escape(`${name}@${from}`)}${WHOLE}`, "g");
        counts[name] = (result.match(pattern) || []).length;
        result = result.replace(pattern, `${name}@${to}`);
    }
    return { text: result, counts };
}

function branchName(changes, auditFix, today) {
    if (changes.length === 0) return `toolchain/audit-fix-${today}`;
    return `toolchain/${changes.map(({ tool, to }) => `${tool}-${to}`).join("-")}${auditFix ? "-audit-fix" : ""}`;
}

function list(items) {
    return items.length === 1 ? items[0] : `${items.slice(0, -1).join(", ")} and ${items[items.length - 1]}`;
}

const bullets = (items) => items.map((item) => `- ${item}`).join("\n");

/**
 * The commit, and the pull request, a toolchain change is released by: a feat for a new
 * version of a tool, a fix for npm audit fixes alone. Its body says what users of the
 * image must change, what npm audit fix changed, which install scripts were approved,
 * and which vulnerability reviews to redo.
 */
function message(changes, { breaking = null, pins = {}, auditFix = false, lockChanges = [], audit = null,
    scripts = null, reviews: stale = [] } = {}) {
    const paragraphs = [];
    let subject;
    if (changes.length > 0) {
        const shipped = list(changes.map(({ name, to }) => `${name} ${to}`));
        subject = `feat(api-only-publisher)${breaking ? "!" : ""}: ship ${shipped} in the image and as init's default`;
        paragraphs.push(
            `The Docker image now carries ${list(changes.map(({ name, from, to }) => `${name} ${to} (was ${from})`))}, ` +
                "and init pins the same in a new apionly.yaml's toolchain.",
            "The image never downloads a toolchain, so an apionly.yaml that pins the old version fails with it: " +
                list(changes.map(({ tool, name, to }) => `set toolchain.${tool} to "${name}@${to}"`)) + " to use this image.");
    } else {
        subject = "fix(api-only-publisher): apply npm audit fixes to the image's toolchain";
        paragraphs.push(`The image keeps ${list(Object.values(pins).sort())}; npm audit fix updated what they install.`);
    }
    if (auditFix) {
        paragraphs.push(lockChanges.length > 0 ? `npm audit fix changed the toolchain's lockfile:\n${bullets(lockChanges)}`
            : "npm audit fix changed nothing in the toolchain's lockfile.");
    }
    if (audit) paragraphs.push(`npm audit of the toolchain: ${audit.after} (was ${audit.before}).`);
    if (scripts && (scripts.added.length > 0 || scripts.removed.length > 0)) {
        const lines = [];
        if (scripts.added.length > 0) lines.push(`Install scripts approved:\n${bullets(scripts.added)}`);
        if (scripts.removed.length > 0) lines.push(`Install scripts no longer installed, their approval removed:\n${bullets(scripts.removed)}`);
        paragraphs.push(lines.join("\n\n"));
    }
    if (stale.length > 0) {
        paragraphs.push(`.trivyignore.yaml records a vulnerability review against a version this replaces; redo it:\n${bullets(stale)}`);
    }
    return { subject, body: paragraphs.join("\n\n"), footer: breaking ? `BREAKING CHANGE: ${breaking}` : null };
}

/** Packages with an install script, as `name@version`, from a lockfile. */
function installScripts(lock) {
    return Object.entries(lock.packages || {})
        .filter(([key, entry]) => key && entry.hasInstallScript)
        .map(([key, entry]) => `${packageName(key)}@${entry.version}`)
        .sort();
}

function packageName(key) {
    return key.slice(key.lastIndexOf("node_modules/") + "node_modules/".length);
}

/** Every package whose version a lockfile change moved, added or removed, as a line each. */
function lockfileChanges(before, after) {
    const versions = (lock) => Object.fromEntries(Object.entries(lock.packages || {}).filter(([key]) => key)
        .map(([key, entry]) => [key, entry.version]));
    const was = versions(before);
    const is = versions(after);
    return [...new Set([...Object.keys(was), ...Object.keys(is)])].sort()
        .filter((key) => was[key] !== is[key])
        .map((key) => `${packageName(key)} ${was[key] || "(added)"} -> ${is[key] || "(removed)"}`);
}

/** npm audit's counts, worst first, as a phrase. */
function auditSummary(report) {
    const counts = ((report || {}).metadata || {}).vulnerabilities || {};
    const parts = ["critical", "high", "moderate", "low", "info"].filter((severity) => counts[severity] > 0)
        .map((severity) => `${counts[severity]} ${severity}`);
    return parts.length > 0 ? parts.join(", ") : "no vulnerabilities";
}

/** Lines of the vulnerability-review record that name a tool at the version it is moving from. */
function reviews(text, changes) {
    return text.split("\n").map((line) => line.trim()).filter((line) =>
        changes.some(({ name, from }) => line.includes(name) && new RegExp(`(^|[^\\w.])${escape(from)}${WHOLE}`).test(line)));
}

/** TOOLCHAIN_PIN, read from init-config.js's source without running it. */
function pinsOf(source) {
    const declaration = /const TOOLCHAIN_PIN = \{([^}]*)\}/.exec(source);
    if (!declaration) throw new ToolchainUpdateError("src/init-config.js on origin/main declares no TOOLCHAIN_PIN");
    return Object.fromEntries([...declaration[1].matchAll(/(\w+):\s*"([^"]+)"/g)].map((m) => [m[1], m[2]]));
}

/**
 * Runs a command and returns its output, throwing when it fails, unless `tolerate`:
 * then a failing command's output is returned all the same, as npm audit's is.
 */
function defaultExec(command, args, { cwd, inherit = false, input, tolerate = false } = {}) {
    const stdio = inherit ? "inherit" : [input === undefined ? "ignore" : "pipe", "pipe", "pipe"];
    try {
        return execFileSync(command, args, { cwd, input, encoding: "utf8", stdio }) || "";
    } catch (error) {
        if (tolerate) return error.stdout || "";
        throw error;
    }
}

const readJson = (file) => JSON.parse(fs.readFileSync(file, "utf8"));
const writeJson = (file, value) => fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);

/**
 * The whole change, from a clean tree to a pull request. `root` is the Publisher's
 * directory; `exec` runs a command, as defaultExec does.
 */
function update(options, { root = path.join(__dirname, ".."), exec = defaultExec, log = console.log,
    today = new Date().toISOString().slice(0, 10) } = {}) {
    const git = (...args) => exec("git", args, { cwd: root }).trim();
    if (git("status", "--porcelain")) throw new ToolchainUpdateError("the working tree has changes: commit or stash them first");
    if (options.smoke) {
        try {
            exec("docker", ["info"], { cwd: root });
        } catch {
            throw new ToolchainUpdateError("Docker is not available to smoke-test the image; run with --skip-smoke to leave that to CI");
        }
    }

    git("fetch", "--quiet", "origin", "main");
    // What is pinned on origin/main, whatever the current branch pins.
    const pins = pinsOf(git("show", "origin/main:./src/init-config.js"));
    const changes = plan(pins, options.versions);
    const branch = branchName(changes, options.auditFix, today);
    for (const { name, from, to, major } of changes) {
        log(`${name}: ${from} -> ${to}`);
        if (major && !options.breaking) {
            log(`  warning: ${to} is a new major version of ${name}; if it changes what a library's build or lint ` +
                "produces, run again with --breaking \"<what users must change>\"");
        }
    }
    const current = git("branch", "--show-current");
    const back = current ? ["switch", "--quiet", current] : ["switch", "--quiet", "--detach", git("rev-parse", "HEAD")];
    git("switch", "--quiet", "-c", branch, "origin/main");
    log(`On a new branch, ${branch}. If a test fails, nothing is committed: inspect it there.`);
    /** Undoes everything so far, for a refusal that leaves nothing to inspect. */
    const abandon = (reason) => {
        git("checkout", "--quiet", "--", ".");
        git(...back);
        git("branch", "--quiet", "-D", branch);
        return new ToolchainUpdateError(`${reason} Nothing was changed.`);
    };

    // The manifest and its lockfile.
    const toolchain = path.join(root, "docker", "toolchain");
    const manifestFile = path.join(toolchain, "package.json");
    const lockFile = path.join(toolchain, "package-lock.json");
    const npmAudit = () => auditSummary(JSON.parse(exec("npm", ["audit", "--package-lock-only", "--json"],
        { cwd: toolchain, tolerate: true }) || "{}"));
    const manifest = readJson(manifestFile);
    const lockBefore = readJson(lockFile);
    const auditBefore = npmAudit();
    for (const { name, to } of changes) manifest.dependencies[name] = to;
    writeJson(manifestFile, manifest);
    if (changes.length > 0) {
        exec("npm", ["install", "--package-lock-only", "--ignore-scripts", "--no-audit", "--no-fund"], { cwd: toolchain, inherit: true });
    }
    if (options.auditFix) {
        // npm audit fix exits non-zero while anything remains unfixed: what it changed is what counts.
        exec("npm", ["audit", "fix", "--package-lock-only", "--ignore-scripts", "--no-fund"], { cwd: toolchain, inherit: true, tolerate: true });
    }
    const lock = readJson(lockFile);
    for (const [name, version] of Object.entries(manifest.dependencies)) {
        const locked = (lock.packages[`node_modules/${name}`] || {}).version;
        if (locked !== version) throw new ToolchainUpdateError(`the lockfile resolved ${name} to ${locked}, not ${version}`);
    }
    const lockChanges = lockfileChanges(lockBefore, lock);
    if (lockChanges.length === 0) throw abandon("npm audit fix changed nothing in the toolchain's lockfile.");

    // Install scripts: approved by name and version, and refused until they are.
    const required = installScripts(lock);
    const approved = Object.keys(manifest.allowScripts || {}).sort();
    const scripts = { added: required.filter((pkg) => !approved.includes(pkg)), removed: approved.filter((pkg) => !required.includes(pkg)) };
    if (scripts.added.length > 0 || scripts.removed.length > 0) {
        if (!options.approveScripts) {
            const lines = [...scripts.added.map((pkg) => `  + ${pkg}`), ...scripts.removed.map((pkg) => `  - ${pkg}`)];
            throw abandon(`the toolchain's install scripts changed:\n${lines.join("\n")}\n` +
                "Review each added package's install script, then run again with --approve-scripts.");
        }
        if (required.length > 0) manifest.allowScripts = Object.fromEntries(required.map((pkg) => [pkg, true]));
        else delete manifest.allowScripts;
        writeJson(manifestFile, manifest);
    }

    // Every tracked file quoting an old pin.
    const totals = Object.fromEntries(changes.map(({ name }) => [name, {}]));
    if (changes.length > 0) {
        for (const file of git("ls-files").split("\n").filter(Boolean)) {
            if (NOT_REWRITTEN.some((pattern) => pattern.test(file))) continue;
            const absolute = path.join(root, file);
            const before = fs.readFileSync(absolute, "utf8");
            const { text, counts } = rewrite(before, changes);
            if (text === before) continue;
            fs.writeFileSync(absolute, text);
            for (const [name, count] of Object.entries(counts)) if (count) totals[name][file] = count;
        }
    }
    for (const { name } of changes) {
        for (const pinned of ["src/init-config.js", "Dockerfile"]) {
            if (!totals[name][pinned]) throw new ToolchainUpdateError(`${pinned} does not pin ${name} as TOOLCHAIN_PIN does`);
        }
        log(`${name} changed in ${list(Object.keys(totals[name]))}`);
    }

    // Tested before anything is committed.
    if (!fs.existsSync(path.join(root, "node_modules"))) exec("npm", ["ci", "--no-audit", "--no-fund"], { cwd: root, inherit: true });
    exec("npm", ["test"], { cwd: root, inherit: true });
    if (options.smoke) {
        try {
            exec("sh", ["docker/build-context.sh", SMOKE_VERSION], { cwd: root, inherit: true });
            exec("docker", ["build", "--build-arg", `VERSION=${SMOKE_VERSION}`, "-t", SMOKE_IMAGE, "."], { cwd: root, inherit: true });
            exec("sh", ["docker/smoke-test.sh", SMOKE_IMAGE, SMOKE_VERSION], { cwd: root, inherit: true });
        } finally {
            fs.rmSync(path.join(root, "api-only-publisher.tgz"), { force: true });
        }
    }

    const trivyignore = path.join(root, ".trivyignore.yaml");
    const stale = fs.existsSync(trivyignore) ? reviews(fs.readFileSync(trivyignore, "utf8"), changes) : [];
    const { subject, body, footer } = message(changes, {
        breaking: options.breaking, pins, auditFix: options.auditFix, lockChanges,
        audit: { before: auditBefore, after: npmAudit() }, scripts, reviews: stale,
    });
    const text = [body, ...(footer ? ["", footer] : [])].join("\n");
    git("add", "--all", ".");
    exec("git", ["commit", "--quiet", "-F", "-"], { cwd: root, input: `${subject}\n\n${text}\n` });
    log(`Committed: ${subject}`);
    for (const line of stale) log(`  redo the review in .trivyignore.yaml, recorded against the old version: ${line}`);

    if (!options.push) {
        log(`Not pushed (--no-push). To release it: git push -u origin ${branch}, and open a pull request titled '${subject}'.`);
        return { branch, subject };
    }
    git("push", "--quiet", "-u", "origin", branch);
    const url = exec("gh", ["pr", "create", "--base", "main", "--head", branch, "--title", subject, "--body", text], { cwd: root }).trim();
    log(`Pull request: ${url}`);
    return { branch, subject, url };
}

if (require.main === module) {
    try {
        const options = parseArgs(process.argv.slice(2));
        if (options.help) process.stdout.write(HELP);
        else update(options);
    } catch (error) {
        if (!(error instanceof ToolchainUpdateError)) throw error;
        process.stderr.write(`toolchain-update: ${error.message}\n`);
        process.exit(1);
    }
}

module.exports = {
    HELP, parseArgs, pinsOf, plan, rewrite, branchName, message, installScripts, lockfileChanges, auditSummary, reviews,
    defaultExec, update, ToolchainUpdateError,
};
