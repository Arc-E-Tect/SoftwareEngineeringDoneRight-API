"use strict";

// Changing the Publisher's toolchain: every place that quotes a pin changes with it,
// install scripts are approved by name, it is tested before it is committed, and it is
// committed and proposed as a feature, or as a fix for npm audit fixes alone.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const {
    HELP, parseArgs, pinsOf, plan, rewrite, branchName, message, installScripts, lockfileChanges, auditSummary, reviews,
    defaultExec, update, ToolchainUpdateError,
} = require("../scripts/toolchain-update");

// Pins no real version of the Publisher carries, so this file never quotes the real
// ones, which the script rewrites wherever they are tracked.
const PINS = { openapi: "@redocly/cli@1.2.3", asyncapi: "@asyncapi/cli@4.5.6" };

const refused = (pattern) => (error) => error instanceof ToolchainUpdateError && pattern.test(error.message);

const DEFAULTS = { versions: {}, breaking: null, auditFix: false, approveScripts: false, smoke: true, push: true };

test("the arguments name the tools to change, and how", () => {
    assert.deepStrictEqual(parseArgs(["--redocly", "1.3.0"]), { ...DEFAULTS, versions: { redocly: "1.3.0" } });
    assert.deepStrictEqual(parseArgs(["--asyncapi", "5.0.0", "--redocly", "1.3.0", "--breaking", " re-pin ", "--audit-fix",
        "--approve-scripts", "--skip-smoke", "--no-push"]), {
        versions: { asyncapi: "5.0.0", redocly: "1.3.0" }, breaking: "re-pin", auditFix: true, approveScripts: true, smoke: false, push: false,
    });
    assert.deepStrictEqual(parseArgs(["--audit-fix"]), { ...DEFAULTS, auditFix: true });
});

test("--help, -h, or no arguments at all ask for the help, which explains every option", () => {
    for (const argv of [[], ["--help"], ["-h"], ["--redocly", "1.3.0", "--help"]]) assert.strictEqual(parseArgs(argv).help, true, argv.join(" "));
    assert.strictEqual(parseArgs(["--redocly", "1.3.0"]).help, undefined);
    for (const option of ["--redocly", "--asyncapi", "--audit-fix", "--breaking", "--approve-scripts", "--skip-smoke", "--no-push", "--help"]) {
        assert.ok(HELP.includes(option), option);
    }
    const script = path.join(__dirname, "..", "scripts", "toolchain-update.js");
    assert.strictEqual(execFileSync("node", [script, "--help"], { encoding: "utf8" }), HELP);
    assert.strictEqual(execFileSync("node", [script], { encoding: "utf8" }), HELP);
    assert.throws(() => execFileSync("node", [script, "--nonsense"], { stdio: "pipe" }),
        (error) => error.status === 1 && /unknown argument '--nonsense'.*\n.*Run it with --help/s.test(error.stderr.toString()));
});

test("the shell wrapper runs the script from any directory, passing its arguments and exit status on", () => {
    const wrapper = path.join(__dirname, "..", "scripts", "toolchain-update.sh");
    const elsewhere = fs.mkdtempSync(path.join(os.tmpdir(), "aop-toolchain-wrapper-"));
    assert.strictEqual(execFileSync(wrapper, ["--help"], { cwd: elsewhere, encoding: "utf8" }), HELP);
    assert.throws(() => execFileSync(wrapper, ["--nonsense"], { cwd: elsewhere, stdio: "pipe" }),
        (error) => error.status === 1 && /unknown argument '--nonsense'/.test(error.stderr.toString()));
});

test("arguments that ask for nothing, for an unknown tool, or with no value are refused with the usage", () => {
    assert.throws(() => parseArgs(["--skip-smoke"]), refused(/name a tool's new version, or --audit-fix\.\nusage/));
    assert.throws(() => parseArgs(["--spectral", "1.0.0"]), refused(/unknown argument '--spectral'/));
    assert.throws(() => parseArgs(["--redocly"]), refused(/--redocly needs a value/));
    assert.throws(() => parseArgs(["--redocly", "--no-push"]), refused(/--redocly needs a value/));
    assert.throws(() => parseArgs(["--redocly", "1.3.0", "--breaking", "  "]), refused(/needs the change users must make/));
    assert.throws(() => parseArgs(["--audit-fix", "--breaking", "x"]), refused(/--breaking goes with a new version/));
});

test("TOOLCHAIN_PIN is read from init-config.js's source", () => {
    assert.deepStrictEqual(pinsOf(`const X = 1;\nconst TOOLCHAIN_PIN = { openapi: "${PINS.openapi}", asyncapi: "${PINS.asyncapi}" };\n`), PINS);
    assert.throws(() => pinsOf("module.exports = {};"), refused(/declares no TOOLCHAIN_PIN/));
});

test("the plan moves each named tool from its pin to a newer version, and says when that is a new major", () => {
    assert.deepStrictEqual(plan(PINS, { redocly: "1.3.0" }),
        [{ tool: "redocly", name: "@redocly/cli", from: "1.2.3", to: "1.3.0", major: false }]);
    assert.deepStrictEqual(plan(PINS, { asyncapi: "5.0.0", redocly: "1.2.4" }), [
        { tool: "redocly", name: "@redocly/cli", from: "1.2.3", to: "1.2.4", major: false },
        { tool: "asyncapi", name: "@asyncapi/cli", from: "4.5.6", to: "5.0.0", major: true },
    ]);
    assert.deepStrictEqual(plan(PINS, {}), []);
});

test("the plan refuses a version that is not newer, not released, or not a version", () => {
    assert.throws(() => plan(PINS, { redocly: "1.2.3" }), refused(/1\.2\.3 is not newer than 1\.2\.3/));
    assert.throws(() => plan(PINS, { redocly: "1.1.9" }), refused(/not newer/));
    assert.throws(() => plan(PINS, { redocly: "1.3.0-rc.1" }), refused(/released versions only/));
    assert.throws(() => plan(PINS, { redocly: "latest" }), refused(/not a semantic version/));
    assert.throws(() => plan({ openapi: PINS.openapi }, { asyncapi: "5.0.0" }), refused(/pins nothing for asyncapi/));
});

test("a rewrite replaces whole pins only, and counts them", () => {
    const changes = plan(PINS, { redocly: "1.3.0", asyncapi: "5.0.0" });
    const { text, counts } = rewrite([
        `redocly: "@redocly/cli@1.2.3"`,
        `label="@redocly/cli@1.2.3" other="@asyncapi/cli@4.5.6"`,
        "@redocly/cli@1.2.30 @redocly/cli@1.2.3-rc.1 @redocly/cli@1.2.3.4 @redocly/cli 1.2.3",
        "This installation carries @redocly/cli@1.2.3.",
    ].join("\n"), changes);
    assert.strictEqual(text, [
        `redocly: "@redocly/cli@1.3.0"`,
        `label="@redocly/cli@1.3.0" other="@asyncapi/cli@5.0.0"`,
        "@redocly/cli@1.2.30 @redocly/cli@1.2.3-rc.1 @redocly/cli@1.2.3.4 @redocly/cli 1.2.3",
        "This installation carries @redocly/cli@1.3.0.",
    ].join("\n"));
    assert.deepStrictEqual(counts, { "@redocly/cli": 3, "@asyncapi/cli": 1 });
});

test("the branch names every version it ships, or the day of an npm audit fix", () => {
    assert.strictEqual(branchName(plan(PINS, { redocly: "1.3.0" }), false, "2026-10-04"), "toolchain/redocly-1.3.0");
    assert.strictEqual(branchName(plan(PINS, { redocly: "1.3.0", asyncapi: "5.0.0" }), true, "2026-10-04"),
        "toolchain/redocly-1.3.0-asyncapi-5.0.0-audit-fix");
    assert.strictEqual(branchName([], true, "2026-10-04"), "toolchain/audit-fix-2026-10-04");
});

test("a new version is a feature, and the commit says what users of the image must change", () => {
    const { subject, body, footer } = message(plan(PINS, { redocly: "1.3.0" }));
    assert.strictEqual(subject, "feat(api-only-publisher): ship @redocly/cli 1.3.0 in the image and as init's default");
    assert.match(body, /now carries @redocly\/cli 1\.3\.0 \(was 1\.2\.3\)/);
    assert.match(body, /set toolchain\.redocly to "@redocly\/cli@1\.3\.0" to use this image/);
    assert.strictEqual(footer, null);
});

test("a breaking toolchain change is marked so in the subject, with its consequence as the footer", () => {
    const { subject, body, footer } = message(plan(PINS, { redocly: "1.3.0", asyncapi: "5.0.0" }), { breaking: "lint rules renamed" });
    assert.strictEqual(subject,
        "feat(api-only-publisher)!: ship @redocly/cli 1.3.0 and @asyncapi/cli 5.0.0 in the image and as init's default");
    assert.match(body, /@redocly\/cli 1\.3\.0 \(was 1\.2\.3\) and @asyncapi\/cli 5\.0\.0 \(was 4\.5\.6\)/);
    assert.match(body, /set toolchain\.redocly to "@redocly\/cli@1\.3\.0" and set toolchain\.asyncapi to "@asyncapi\/cli@5\.0\.0"/);
    assert.strictEqual(footer, "BREAKING CHANGE: lint rules renamed");
});

test("npm audit fixes alone are a fix, and the commit lists what changed, what was approved and what to review", () => {
    const { subject, body, footer } = message([], {
        pins: PINS, auditFix: true, lockChanges: ["dep 1.0.0 -> 1.0.1"], audit: { before: "1 high", after: "no vulnerabilities" },
        scripts: { added: ["fresh@1.0.0"], removed: ["old@0.1.0"] }, reviews: ["statement: Reviewed against @asyncapi/cli 4.5.6."],
    });
    assert.strictEqual(subject, "fix(api-only-publisher): apply npm audit fixes to the image's toolchain");
    assert.strictEqual(body, [
        "The image keeps @asyncapi/cli@4.5.6 and @redocly/cli@1.2.3; npm audit fix updated what they install.",
        "",
        "npm audit fix changed the toolchain's lockfile:\n- dep 1.0.0 -> 1.0.1",
        "",
        "npm audit of the toolchain: no vulnerabilities (was 1 high).",
        "",
        "Install scripts approved:\n- fresh@1.0.0\n\nInstall scripts no longer installed, their approval removed:\n- old@0.1.0",
        "",
        ".trivyignore.yaml records a vulnerability review against a version this replaces; redo it:\n" +
            "- statement: Reviewed against @asyncapi/cli 4.5.6.",
    ].join("\n"));
    assert.strictEqual(footer, null);
    assert.match(message([], { pins: PINS, auditFix: true }).body, /npm audit fix changed nothing/);
});

test("install scripts are read from the lockfile, nested packages included", () => {
    assert.deepStrictEqual(installScripts({
        packages: {
            "": { hasInstallScript: true },
            "node_modules/b": { version: "1.0.0", hasInstallScript: true },
            "node_modules/a/node_modules/@scope/c": { version: "2.0.0", hasInstallScript: true },
            "node_modules/d": { version: "3.0.0" },
        },
    }), ["@scope/c@2.0.0", "b@1.0.0"]);
    assert.deepStrictEqual(installScripts({}), []);
});

test("a lockfile change is every package moved, added or removed", () => {
    assert.deepStrictEqual(lockfileChanges(
        { packages: { "": { version: "0" }, "node_modules/a": { version: "1.0.0" }, "node_modules/b": { version: "1.0.0" }, "node_modules/c": { version: "1.0.0" } } },
        { packages: { "": { version: "1" }, "node_modules/a": { version: "1.0.1" }, "node_modules/c": { version: "1.0.0" }, "node_modules/x/node_modules/d": { version: "2.0.0" } } }),
    ["a 1.0.0 -> 1.0.1", "b 1.0.0 -> (removed)", "d (added) -> 2.0.0"]);
    assert.deepStrictEqual(lockfileChanges({}, {}), []);
});

test("npm audit's counts read worst first", () => {
    assert.strictEqual(auditSummary({ metadata: { vulnerabilities: { info: 0, low: 4, moderate: 0, high: 23, critical: 6, total: 33 } } }),
        "6 critical, 23 high, 4 low");
    assert.strictEqual(auditSummary({ metadata: { vulnerabilities: { total: 0 } } }), "no vulnerabilities");
    assert.strictEqual(auditSummary({}), "no vulnerabilities");
});

test("a vulnerability review against an old version is found, and one against another version is not", () => {
    const changes = plan(PINS, { asyncapi: "5.0.0" });
    assert.deepStrictEqual(reviews([
        "  - id: CVE-1",
        "    statement: Reviewed 2026-09-29 against @asyncapi/cli 4.5.6.",
        "    statement: Reviewed against @asyncapi/cli 4.5.60.",
        "    statement: Reviewed against something else 4.5.6.",
    ].join("\n"), changes), ["statement: Reviewed 2026-09-29 against @asyncapi/cli 4.5.6."]);
});

test("a command's output is returned, and a failing one's only when it is tolerated", () => {
    const script = (code) => ["-e", `process.stdout.write("out"); process.exit(${code})`];
    assert.strictEqual(defaultExec(process.execPath, script(0)), "out");
    assert.strictEqual(defaultExec(process.execPath, script(1), { tolerate: true }), "out");
    assert.throws(() => defaultExec(process.execPath, script(1)));
    assert.strictEqual(defaultExec(process.execPath, ["-e", "process.stdin.pipe(process.stdout)"], { input: "in" }), "in");
});

/** The toolchain's lockfile as npm would write it for `dependencies`, beside a transitive `dep`. */
function lockfile(dependencies, { dep = "1.0.0", fresh = false } = {}) {
    const packages = { "": { dependencies } };
    for (const [name, version] of Object.entries(dependencies)) {
        packages[`node_modules/${name}`] = { version, ...(name === "@asyncapi/cli" ? { hasInstallScript: true } : {}) };
    }
    packages["node_modules/dep"] = { version: dep };
    if (fresh) packages["node_modules/fresh"] = { version: "1.0.0", hasInstallScript: true };
    return `${JSON.stringify({ packages }, null, 2)}\n`;
}

/**
 * A repository holding a Publisher that pins PINS, pushed to a bare origin, and an
 * exec that runs git for real and stands in for npm, Docker and gh. npm audit finds a
 * high vulnerability while `dep` is 1.0.0, which npm audit fix moves to 1.0.1.
 */
function workspace({ failing = null, lockedRedocly = null, dockerAvailable = true, fresh = false, auditFixes = true } = {}) {
    const base = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "aop-toolchain-update-")));
    const origin = path.join(base, "origin.git");
    const repo = path.join(base, "repo");
    const root = path.join(repo, "publisher");
    const git = (cwd, ...args) => execFileSync("git", args, { cwd, encoding: "utf8", stdio: "pipe" }).trim();
    execFileSync("git", ["init", "--quiet", "--bare", "--initial-branch=main", origin]);
    execFileSync("git", ["init", "--quiet", "--initial-branch=main", repo]);
    for (const [key, value] of [["user.email", "test@example.com"], ["user.name", "Test"], ["commit.gpgsign", "false"]]) {
        git(repo, "config", key, value);
    }
    const dependencies = { "@asyncapi/cli": "4.5.6", "@redocly/cli": "1.2.3" };
    const files = {
        "src/init-config.js": `const TOOLCHAIN_PIN = { openapi: "${PINS.openapi}", asyncapi: "${PINS.asyncapi}" };\n`,
        Dockerfile: `LABEL redocly="${PINS.openapi}" \\\n      asyncapi="${PINS.asyncapi}"\n`,
        "README.adoc": `Pin "${PINS.openapi}", not "@redocly/cli@1.2.30".\n`,
        "CHANGELOG.md": `Shipped ${PINS.openapi}.\n`,
        ".trivyignore.yaml": "statement: Reviewed against @asyncapi/cli 4.5.6.\n",
        "docker/toolchain/package.json": `${JSON.stringify({ name: "t", private: true, dependencies,
            allowScripts: { "@asyncapi/cli@4.5.6": true } }, null, 2)}\n`,
        "docker/toolchain/package-lock.json": lockfile(dependencies),
        "docker/fixture.bin": "binary \u0000 data\n",
    };
    for (const [file, content] of Object.entries(files)) {
        fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
        fs.writeFileSync(path.join(root, file), content);
    }
    git(repo, "add", "--all");
    git(repo, "commit", "--quiet", "-m", "the Publisher");
    git(repo, "remote", "add", "origin", origin);
    git(repo, "push", "--quiet", "-u", "origin", "main");

    const calls = [];
    const exec = (command, args, { cwd, input } = {}) => {
        if (command === "git") return execFileSync("git", args, { cwd, input, encoding: "utf8", stdio: "pipe" });
        const call = [command, ...args].join(" ");
        calls.push(call);
        if (failing && call.startsWith(failing)) throw new Error(`${call} failed`);
        if (call === "docker info" && !dockerAvailable) throw new Error("no daemon");
        const lockFile = path.join(cwd, "package-lock.json");
        const dep = () => JSON.parse(fs.readFileSync(lockFile, "utf8")).packages["node_modules/dep"].version;
        if (call.startsWith("npm install --package-lock-only")) {
            const manifest = JSON.parse(fs.readFileSync(path.join(cwd, "package.json"), "utf8"));
            const locked = { ...manifest.dependencies, ...(lockedRedocly ? { "@redocly/cli": lockedRedocly } : {}) };
            fs.writeFileSync(lockFile, lockfile(locked, { dep: dep(), fresh }));
        }
        if (call.startsWith("npm audit fix") && auditFixes) {
            fs.writeFileSync(lockFile, fs.readFileSync(lockFile, "utf8").replace(/("node_modules\/dep": \{\s*"version": )"1\.0\.0"/, "$1\"1.0.1\""));
        }
        if (call === "npm audit --package-lock-only --json") {
            return JSON.stringify({ metadata: { vulnerabilities: { high: dep() === "1.0.0" ? 1 : 0 } } });
        }
        if (call.startsWith("sh docker/build-context.sh")) fs.writeFileSync(path.join(cwd, "api-only-publisher.tgz"), "tarball");
        if (command === "gh") return "https://github.com/example/repo/pull/1\n";
        return "";
    };
    const read = (file) => fs.readFileSync(path.join(root, file), "utf8");
    return { root, repo, origin, git, calls, exec, read, log: () => {}, today: "2026-10-04" };
}

const AUDIT = "npm audit --package-lock-only --json";
const INSTALL = "npm install --package-lock-only --ignore-scripts --no-audit --no-fund";
const AUDIT_FIX = "npm audit fix --package-lock-only --ignore-scripts --no-fund";

test("an update changes every quoted pin, tests, smoke-tests, commits a feature and proposes it", () => {
    const w = workspace();
    const messages = [];
    const result = update(parseArgs(["--redocly", "1.3.0"]), { ...w, log: (line) => messages.push(line) });

    assert.deepStrictEqual(result, {
        branch: "toolchain/redocly-1.3.0",
        subject: "feat(api-only-publisher): ship @redocly/cli 1.3.0 in the image and as init's default",
        url: "https://github.com/example/repo/pull/1",
    });
    assert.match(w.read("src/init-config.js"), /openapi: "@redocly\/cli@1\.3\.0", asyncapi: "@asyncapi\/cli@4\.5\.6"/);
    assert.match(w.read("Dockerfile"), /redocly="@redocly\/cli@1\.3\.0"/);
    assert.strictEqual(w.read("README.adoc"), `Pin "@redocly/cli@1.3.0", not "@redocly/cli@1.2.30".\n`);
    assert.strictEqual(w.read("CHANGELOG.md"), `Shipped ${PINS.openapi}.\n`);
    const manifest = JSON.parse(w.read("docker/toolchain/package.json"));
    assert.strictEqual(manifest.dependencies["@redocly/cli"], "1.3.0");
    assert.deepStrictEqual(manifest.allowScripts, { "@asyncapi/cli@4.5.6": true }, "no install script changed");
    assert.strictEqual(fs.existsSync(path.join(w.root, "api-only-publisher.tgz")), false);

    const body = w.git(w.repo, "log", "-1", "--format=%b");
    assert.deepStrictEqual(w.calls, [
        "docker info",
        AUDIT,
        INSTALL,
        "npm ci --no-audit --no-fund",
        "npm test",
        "sh docker/build-context.sh 0.0.0-toolchain",
        "docker build --build-arg VERSION=0.0.0-toolchain -t api-only-publisher:toolchain-update .",
        "sh docker/smoke-test.sh api-only-publisher:toolchain-update 0.0.0-toolchain",
        AUDIT,
        `gh pr create --base main --head toolchain/redocly-1.3.0 --title ${result.subject} --body ${body}`,
    ]);
    assert.strictEqual(w.git(w.repo, "log", "-1", "--format=%s"), result.subject);
    assert.match(body, /npm audit of the toolchain: 1 high \(was 1 high\)\./);
    assert.doesNotMatch(body, /Install scripts|trivyignore|npm audit fix/);
    assert.strictEqual(w.git(w.repo, "status", "--porcelain"), "");
    assert.strictEqual(w.git(w.origin, "rev-parse", "toolchain/redocly-1.3.0"), w.git(w.repo, "rev-parse", "HEAD"));
    assert.ok(messages.some((line) => line.includes("@redocly/cli changed in Dockerfile, README.adoc and src/init-config.js")));
});

test("a breaking update with new install scripts commits feat! with its footer, the approvals and the review to redo", () => {
    const w = workspace({ fresh: true });
    fs.mkdirSync(path.join(w.root, "node_modules"));
    const messages = [];
    update(parseArgs(["--asyncapi", "5.0.0", "--breaking", "re-pin toolchain.asyncapi", "--approve-scripts", "--skip-smoke", "--no-push"]),
        { ...w, log: (line) => messages.push(line) });

    assert.deepStrictEqual(w.calls, [AUDIT, INSTALL, "npm test", AUDIT]);
    assert.deepStrictEqual(JSON.parse(w.read("docker/toolchain/package.json")).allowScripts,
        { "@asyncapi/cli@5.0.0": true, "fresh@1.0.0": true });
    assert.strictEqual(w.git(w.repo, "log", "-1", "--format=%s"),
        "feat(api-only-publisher)!: ship @asyncapi/cli 5.0.0 in the image and as init's default");
    const body = w.git(w.repo, "log", "-1", "--format=%b");
    assert.match(body, /Install scripts approved:\n- @asyncapi\/cli@5\.0\.0\n- fresh@1\.0\.0/);
    assert.match(body, /their approval removed:\n- @asyncapi\/cli@4\.5\.6/);
    assert.match(body, /redo it:\n- statement: Reviewed against @asyncapi\/cli 4\.5\.6\./);
    assert.match(body, /BREAKING CHANGE: re-pin toolchain\.asyncapi$/);
    assert.throws(() => w.git(w.origin, "rev-parse", "--verify", "--quiet", "toolchain/asyncapi-5.0.0"));
    assert.ok(!messages.some((line) => line.includes("new major version")), "--breaking was given");
    assert.ok(messages.some((line) => line.includes("redo the review in .trivyignore.yaml")));
    assert.ok(messages.some((line) => line.includes("Not pushed (--no-push)")));
});

test("changed install scripts are refused until approved, leaving nothing changed", () => {
    const w = workspace();
    fs.writeFileSync(path.join(w.root, "untracked.txt"), "kept\n");
    w.git(w.repo, "add", "publisher/untracked.txt");
    w.git(w.repo, "commit", "--quiet", "-m", "a file");
    w.git(w.repo, "switch", "--quiet", "-c", "work");
    assert.throws(() => update(parseArgs(["--asyncapi", "4.6.0", "--skip-smoke"]), w),
        refused(/install scripts changed:\n {2}\+ @asyncapi\/cli@4\.6\.0\n {2}- @asyncapi\/cli@4\.5\.6\n.*--approve-scripts\. Nothing was changed\./s));
    assert.strictEqual(w.git(w.repo, "branch", "--show-current"), "work");
    assert.strictEqual(w.git(w.repo, "status", "--porcelain"), "");
    assert.strictEqual(w.git(w.repo, "branch", "--list", "toolchain/*"), "");
});

test("a refusal from a detached HEAD returns to that commit", () => {
    const w = workspace();
    const head = w.git(w.repo, "rev-parse", "HEAD");
    w.git(w.repo, "switch", "--quiet", "--detach", head);
    assert.throws(() => update(parseArgs(["--asyncapi", "4.6.0", "--skip-smoke"]), w), refused(/install scripts changed/));
    assert.strictEqual(w.git(w.repo, "branch", "--show-current"), "");
    assert.strictEqual(w.git(w.repo, "rev-parse", "HEAD"), head);
});

test("npm audit fixes alone are committed as a fix, listing what changed", () => {
    const w = workspace();
    const result = update(parseArgs(["--audit-fix", "--skip-smoke", "--no-push"]), w);

    assert.deepStrictEqual(result, { branch: "toolchain/audit-fix-2026-10-04",
        subject: "fix(api-only-publisher): apply npm audit fixes to the image's toolchain" });
    assert.deepStrictEqual(w.calls, [AUDIT, AUDIT_FIX, "npm ci --no-audit --no-fund", "npm test", AUDIT]);
    assert.strictEqual(w.read("src/init-config.js"), `const TOOLCHAIN_PIN = { openapi: "${PINS.openapi}", asyncapi: "${PINS.asyncapi}" };\n`);
    const body = w.git(w.repo, "log", "-1", "--format=%b");
    assert.match(body, /The image keeps @asyncapi\/cli@4\.5\.6 and @redocly\/cli@1\.2\.3/);
    assert.match(body, /lockfile:\n- dep 1\.0\.0 -> 1\.0\.1/);
    assert.match(body, /npm audit of the toolchain: no vulnerabilities \(was 1 high\)\./);
});

test("a new version and npm audit fixes go in one feature", () => {
    const w = workspace();
    const result = update(parseArgs(["--redocly", "1.3.0", "--audit-fix", "--skip-smoke", "--no-push"]), w);
    assert.strictEqual(result.branch, "toolchain/redocly-1.3.0-audit-fix");
    assert.deepStrictEqual(w.calls.slice(0, 3), [AUDIT, INSTALL, AUDIT_FIX]);
    assert.match(w.git(w.repo, "log", "-1", "--format=%s"), /^feat\(api-only-publisher\): ship @redocly\/cli 1\.3\.0/);
    assert.match(w.git(w.repo, "log", "-1", "--format=%b"), /- @redocly\/cli 1\.2\.3 -> 1\.3\.0\n- dep 1\.0\.0 -> 1\.0\.1/);
});

test("npm audit fix that changes nothing is refused, leaving nothing changed", () => {
    const w = workspace({ auditFixes: false });
    assert.throws(() => update(parseArgs(["--audit-fix", "--skip-smoke"]), w), refused(/changed nothing.*Nothing was changed/));
    assert.strictEqual(w.git(w.repo, "branch", "--show-current"), "main");
    assert.strictEqual(w.git(w.repo, "status", "--porcelain"), "");
});

test("a new major version without --breaking is a feature, with a warning", () => {
    const w = workspace();
    const messages = [];
    update(parseArgs(["--redocly", "2.0.0", "--skip-smoke", "--no-push"]), { ...w, log: (line) => messages.push(line) });
    assert.match(w.git(w.repo, "log", "-1", "--format=%s"), /^feat\(api-only-publisher\): /);
    assert.ok(messages.some((line) => line.includes("2.0.0 is a new major version of @redocly/cli")));
});

test("an update refuses a working tree with changes, and an absent Docker unless the smoke test is skipped", () => {
    const dirty = workspace();
    fs.writeFileSync(path.join(dirty.root, "README.adoc"), "changed\n");
    assert.throws(() => update(parseArgs(["--redocly", "1.3.0"]), dirty), refused(/working tree has changes/));

    const noDocker = workspace({ dockerAvailable: false });
    assert.throws(() => update(parseArgs(["--redocly", "1.3.0"]), noDocker), refused(/Docker is not available.*--skip-smoke/));
    assert.strictEqual(noDocker.git(noDocker.repo, "branch", "--show-current"), "main");
});

test("a failing test, smoke test or lockfile stops before anything is committed", () => {
    for (const failing of ["npm test", "sh docker/smoke-test.sh"]) {
        const w = workspace({ failing });
        assert.throws(() => update(parseArgs(["--redocly", "1.3.0", "--no-push"]), w), new RegExp(`${failing}.* failed`));
        assert.strictEqual(w.git(w.repo, "log", "-1", "--format=%s"), "the Publisher");
        assert.strictEqual(w.git(w.repo, "branch", "--show-current"), "toolchain/redocly-1.3.0");
        assert.strictEqual(fs.existsSync(path.join(w.root, "api-only-publisher.tgz")), false);
    }
    const w = workspace({ lockedRedocly: "1.2.9" });
    assert.throws(() => update(parseArgs(["--redocly", "1.3.0", "--skip-smoke"]), w), refused(/resolved @redocly\/cli to 1\.2\.9, not 1\.3\.0/));
});

test("an update refuses a Publisher whose image does not pin what TOOLCHAIN_PIN does", () => {
    const w = workspace();
    fs.writeFileSync(path.join(w.root, "Dockerfile"), "LABEL nothing=pinned\n");
    w.git(w.repo, "commit", "--quiet", "-am", "drop the labels");
    w.git(w.repo, "push", "--quiet");
    assert.throws(() => update(parseArgs(["--redocly", "1.3.0", "--skip-smoke"]), w), refused(/Dockerfile does not pin @redocly\/cli/));
});
