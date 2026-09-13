"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { main } = require("../src/cli");
const { loadFrom } = require("../src/config");
const { prepare } = require("../src/pipeline");
const { forTargets } = require("../src/closure");

test("lint writes the validator report when validation succeeds", async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"), `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
defaults:
  openapi:
    outputName: openapi.yaml
build:
  dist: dist
toolchain:
  redocly: "@redocly/cli@2.52.0"
targets:
  example:
    openapi:
      bundle: bundles/example.yaml
`);
    const output = path.join(dir, "dist", "example", "openapi.yaml");
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, "openapi: 3.1.1\ninfo:\n  title: Example\n  version: 1.0.0\npaths: {}\n");

    const bin = path.join(dir, "bin");
    fs.mkdirSync(bin);
    const npx = path.join(bin, "npx");
    fs.writeFileSync(npx, "#!/bin/sh\nprintf 'validator report: no errors\\n'\n");
    fs.chmodSync(npx, 0o755);

    const previousPath = process.env.PATH;
    const messages = [];
    const previousLog = console.log;
    process.env.PATH = `${bin}:${previousPath}`;
    console.log = (message) => messages.push(message);
    try {
        await main(["lint", "-C", dir]);
    } finally {
        process.env.PATH = previousPath;
        console.log = previousLog;
    }

    assert.ok(messages.includes("validator report: no errors"));
    assert.strictEqual(
        fs.readFileSync(path.join(dir, "build", "reports", "lint", "example", "openapi.txt"), "utf8"),
        "validator report: no errors\n"
    );
});

test("lint writes the validator report when validation fails", async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"), `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
defaults:
  openapi:
    outputName: openapi.yaml
build:
  dist: dist
toolchain:
  redocly: "@redocly/cli@2.52.0"
targets:
  example:
    openapi:
      bundle: bundles/example.yaml
`);
    const output = path.join(dir, "dist", "example", "openapi.yaml");
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, "openapi: 3.1.1\ninfo:\n  title: Example\n  version: 1.0.0\npaths: {}\n");

    const bin = path.join(dir, "bin");
    fs.mkdirSync(bin);
    const npx = path.join(bin, "npx");
    fs.writeFileSync(npx, "#!/bin/sh\nprintf 'validator report: errors found\\n' >&2\nexit 1\n");
    fs.chmodSync(npx, 0o755);

    const previousPath = process.env.PATH;
    process.env.PATH = `${bin}:${previousPath}`;
    try {
        await assert.rejects(() => main(["lint", "-C", dir]), /npx .* failed/);
    } finally {
        process.env.PATH = previousPath;
    }

    assert.strictEqual(
        fs.readFileSync(path.join(dir, "build", "reports", "lint", "example", "openapi.txt"), "utf8"),
        "validator report: errors found\n"
    );
});

// ------------------------------------------------------- the other commands
//
// Each command is driven through main(), as the command line runs it, against a
// miniature library: alpha is published and reaches a shared schema; beta is a
// documentation view (publish: false) that reaches nothing.

const LIBRARY = {
    "apionly.yaml": `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
defaults:
  openapi:
    outputName: openapi.yaml
build:
  staging: build/staging
  dist: dist
toolchain:
  redocly: "@redocly/cli@2.52.0"
channels:
  file:
    directory: build/publish
targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
  beta:
    publish: false
    openapi:
      bundle: bundles/beta.yaml
`,
    "specs/openapi/bundles/alpha.yaml":
        "openapi: 3.1.1\ninfo:\n  title: Alpha\n  version: 0.0.0\npaths:\n  /a:\n    $ref: '../paths/A.yaml'\n",
    "specs/openapi/bundles/alpha.bundle.properties": "# The alpha contract.\nversion=1.0.0\n",
    "specs/openapi/bundles/beta.yaml": "openapi: 3.1.1\ninfo:\n  title: Beta\n  version: 0.0.0\npaths: {}\n",
    "specs/openapi/paths/A.yaml": "get:\n  x:\n    $ref: '../components/common/Shared.yaml'\n",
    "specs/openapi/components/common/Shared.yaml": "type: string\n",
};

// Stands in for both bundler calls build makes: writes a document wherever
// --output points, and passes anything else (lint) silently.
const FAKE_TOOLCHAIN = [
    "#!/bin/sh",
    "while [ \"$#\" -gt 0 ]; do",
    "  if [ \"$1\" = \"--output\" ]; then printf 'openapi: 3.1.1\\ninfo:\\n  title: X\\n  version: 0.0.0\\npaths: {}\\n' > \"$2\"; fi",
    "  shift",
    "done",
    "",
].join("\n");

function library(files = LIBRARY) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    for (const [rel, content] of Object.entries(files)) {
        const file = path.join(dir, rel);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, content);
    }
    return dir;
}

// A target as build leaves it: its document in dist/, stamped with the version.
function built(dir, target, version) {
    const dist = path.join(dir, "dist", target);
    fs.mkdirSync(dist, { recursive: true });
    fs.writeFileSync(path.join(dist, "openapi.yaml"),
        `openapi: 3.1.1\ninfo:\n  title: X\n  version: ${version}\npaths: {}\n`);
}

// Runs main() as the command line would, collecting what it prints.
async function run(argv) {
    const printed = [];
    const previousLog = console.log;
    console.log = (message) => printed.push(String(message));
    try {
        return { code: await main(argv), printed };
    } finally {
        console.log = previousLog;
    }
}

async function withToolchain(dir, script, action) {
    const bin = path.join(dir, "bin");
    fs.mkdirSync(bin, { recursive: true });
    fs.writeFileSync(path.join(bin, "npx"), script);
    fs.chmodSync(path.join(bin, "npx"), 0o755);
    const previousPath = process.env.PATH;
    process.env.PATH = `${bin}:${previousPath}`;
    try {
        return await action();
    } finally {
        process.env.PATH = previousPath;
    }
}

test("help, or no command at all, prints the usage and succeeds", async () => {
    for (const argv of [[], ["--help"], ["-h"]]) {
        const written = [];
        const previousWrite = process.stdout.write;
        let pending;
        // The usage is written before main() first awaits, so stdout is borrowed
        // only for the synchronous part of the call.
        process.stdout.write = (chunk) => { written.push(String(chunk)); return true; };
        try {
            pending = main(argv);
        } finally {
            process.stdout.write = previousWrite;
        }
        assert.strictEqual(await pending, 0);
        assert.match(written.join(""), /^api-only-publisher -- build and distribute/);
    }
});

test("an option missing its value, or an unknown option, is refused", async () => {
    await assert.rejects(() => main(["build", "--target"]), /--target requires a value/);
    await assert.rejects(() => main(["build", "--frobnicate"]), /unrecognized option '--frobnicate'/);
});

test("an unknown command, or a target the configuration does not declare, is refused", async () => {
    const dir = library();
    await assert.rejects(() => main(["frobnicate", "-C", dir]), /unrecognized command 'frobnicate'/);
    await assert.rejects(() => main(["targets", "--target", "gamma", "-C", dir]),
        /unknown target 'gamma'; declared targets are alpha, beta/);
});

test("init scaffolds a library the other commands can load", async () => {
    const parent = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    const { code, printed } = await run(["init", "lib", "-C", parent]);

    assert.strictEqual(code, 0);
    assert.ok(printed.includes(`Scaffolding a specification library in ${path.join(parent, "lib")}`));
    const { printed: targets } = await run(["targets", "-C", path.join(parent, "lib")]);
    assert.match(targets.join("\n"), /^example-service {2}\[openapi/);
});

test("targets lists every target, its kinds, and which are not published", async () => {
    const { printed } = await run(["targets", "-C", library()]);
    assert.deepStrictEqual(printed, ["alpha  [openapi]", "beta  [openapi]  (publish: false)"]);
});

test("build stamps each published target with the version its version file declares", async () => {
    const dir = library();
    await withToolchain(dir, FAKE_TOOLCHAIN, () => run(["build", "--openapi", "-C", dir]));

    assert.match(fs.readFileSync(path.join(dir, "dist", "alpha", "openapi.yaml"), "utf8"), /version: 1\.0\.0\n/);
    // A documentation view is never published, so it needs no version and is not stamped.
    assert.match(fs.readFileSync(path.join(dir, "dist", "beta", "openapi.yaml"), "utf8"), /version: 0\.0\.0\n/);
});

test("build --pre-release stamps the declared version with the pre-release appended", async () => {
    const dir = library();
    await withToolchain(dir, FAKE_TOOLCHAIN, () => run(["build", "--pre-release", "rc.1", "-C", dir]));

    assert.match(fs.readFileSync(path.join(dir, "dist", "alpha", "openapi.yaml"), "utf8"), /version: 1\.0\.0-rc\.1\n/);
});

test("build --target builds that target and no other", async () => {
    const dir = library();
    await withToolchain(dir, FAKE_TOOLCHAIN, () => run(["build", "--target", "alpha", "-q", "-C", dir]));

    assert.ok(fs.existsSync(path.join(dir, "dist", "alpha", "openapi.yaml")));
    assert.ok(!fs.existsSync(path.join(dir, "dist", "beta")));
});

test("build refuses a published target with no version file, before building anything", async () => {
    const dir = library();
    fs.rmSync(path.join(dir, "specs/openapi/bundles/alpha.bundle.properties"));

    await assert.rejects(
        () => withToolchain(dir, FAKE_TOOLCHAIN, () => main(["build", "-C", dir])),
        /target 'alpha': no version file at .*alpha\.bundle\.properties/);
    assert.ok(!fs.existsSync(path.join(dir, "dist")));
});

test("--version is refused, saying where a version comes from instead", async () => {
    await assert.rejects(() => main(["build", "--version", "1.0.0"]), /--version .*version file.*--pre-release/s);
});

test("lint refuses a document that was never built, and --target lints only that one", async () => {
    const dir = library();
    await assert.rejects(() => main(["lint", "--target", "alpha", "-C", dir]), /does not exist; run 'build' first/);

    built(dir, "beta", "1.0.0");
    const { printed } = await withToolchain(dir, FAKE_TOOLCHAIN, () => run(["lint", "--target", "beta", "-C", dir]));
    assert.ok(printed.includes("=== beta (openapi) ==="));
    assert.ok(!printed.includes("=== alpha (openapi) ==="));
});

test("closure reports each target's files and hash, and --target narrows it", async () => {
    const dir = library();
    const { printed: all } = await run(["closure", "-C", dir]);
    assert.strictEqual(all.length, 2);
    assert.match(all[0], /^alpha {2}3 file\(s\) {2}[0-9a-f]{64}$/);
    assert.match(all[1], /^beta {2}1 file\(s\) {2}[0-9a-f]{64}$/);

    const { printed: one } = await run(["closure", "--target", "beta", "-C", dir]);
    assert.deepStrictEqual(one, [all[1]]);
});

test("changed names only the targets an edit reaches, bare when quiet", async () => {
    const dir = library({ ...LIBRARY, ".gitignore": "build/\n" });
    const git = (...args) => execFileSync("git", args, { cwd: dir, encoding: "utf8" });
    git("init", "-q", "-b", "main");
    git("config", "user.email", "test@example.invalid");
    git("config", "user.name", "Test");
    git("config", "commit.gpgsign", "false");
    git("add", "-A");
    git("commit", "-qm", "initial");
    fs.writeFileSync(path.join(dir, "specs/openapi/components/common/Shared.yaml"), "type: integer\n");

    await assert.rejects(() => main(["changed", "-C", dir]), /changed requires --since <ref>/);
    const { printed: quiet } = await run(["changed", "--since", "HEAD", "-q", "-C", dir]);
    assert.deepStrictEqual(quiet, ["alpha"]);
    const { printed: verbose } = await run(["changed", "--since", "HEAD", "-C", dir]);
    assert.ok(verbose.includes("\n1 of 2 target(s) changed since HEAD."));
});

test("pack archives each published target at its declared version, and skips documentation views", async () => {
    const dir = library();
    prepare(loadFrom(dir));
    built(dir, "alpha", "1.0.0");

    const { printed } = await run(["pack", "-C", dir]);
    assert.ok(fs.existsSync(path.join(dir, "build", "packages", "alpha-1.0.0.tgz")));
    assert.ok(printed.includes("beta: not packed (publish: false)"));

    await run(["pack", "--target", "alpha", "--out", "elsewhere", "-C", dir]);
    assert.ok(fs.existsSync(path.join(dir, "elsewhere", "alpha-1.0.0.tgz")));

    built(dir, "alpha", "1.0.0-rc.1");
    await run(["pack", "--pre-release", "rc.1", "-C", dir]);
    assert.ok(fs.existsSync(path.join(dir, "build", "packages", "alpha-1.0.0-rc.1.tgz")));
});

test("pack refuses a document built as another version than its version file declares", async () => {
    const dir = library();
    prepare(loadFrom(dir));
    built(dir, "alpha", "0.9.0");

    await assert.rejects(() => main(["pack", "-C", dir]),
        /declares info\.version '0\.9\.0' but is being packed as '1\.0\.0'/);
});

test("publish ships each published target to every configured channel, or only those named", async () => {
    const dir = library();
    prepare(loadFrom(dir));
    built(dir, "alpha", "1.0.0");

    const { printed } = await run(["publish", "-C", dir]);
    assert.ok(fs.existsSync(path.join(dir, "build", "publish", "alpha", "1.0.0", "alpha-1.0.0.tgz")));
    assert.ok(!fs.existsSync(path.join(dir, "build", "publish", "beta")));
    assert.ok(printed.includes("\nPublished 1 artifact(s)."));

    const { printed: named } = await run(["publish", "--target", "alpha", "--channel", "file", "-C", dir]);
    assert.ok(named.includes("\nPublished 1 artifact(s)."));
});

test("publish refuses to run with no channel to ship to", async () => {
    const dir = library({
        ...LIBRARY,
        "apionly.yaml": LIBRARY["apionly.yaml"].replace("channels:\n  file:\n    directory: build/publish\n", ""),
    });
    await assert.rejects(() => main(["publish", "-C", dir]), /no channels configured/);
});

test("split writes self-contained parts, and says when it had to copy shared fragments", async () => {
    const crossing = library({
        "apionly.yaml": `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
  asyncapi: asyncapi
defaults:
  openapi:
    outputName: openapi.yaml
  asyncapi:
    outputName: asyncapi.yaml
targets:
  svc:
    openapi:
      bundle: bundles/svc.yaml
    asyncapi:
      bundle: svc.yaml
`,
        "specs/openapi/bundles/svc.yaml": "paths:\n  /a:\n    $ref: '../paths/A.yaml'\n",
        "specs/openapi/paths/A.yaml": "get:\n  x:\n    $ref: '../components/common/Username.yaml'\n",
        "specs/openapi/components/common/Username.yaml": "type: string\n",
        "specs/asyncapi/svc.yaml": "channels:\n  c:\n    $ref: 'messages/M.yaml'\n",
        "specs/asyncapi/messages/M.yaml": "payload:\n  $ref: '../../openapi/components/common/Username.yaml'\n",
    });
    const { printed: copied } = await run(["split", "--out", "parts", "-C", crossing]);
    assert.ok(copied.some((line) => /^\nWrote \d+ part\(s\) to .*parts\.$/.test(line)));
    assert.ok(copied.some((line) => /shared fragment\(s\) were copied/.test(line)));

    const { printed: contained } = await run(["split", "-C", library()]);
    assert.ok(contained.some((line) => /Wrote \d+ part\(s\) to .*split\.$/.test(line)));
    assert.ok(!contained.some((line) => /were copied/.test(line)));
});

// ------------------------------------ pack and publish hash only what they ship
//
// Every archive records the closure hash of the target it holds. A target that is
// not being shipped -- not selected, or never published -- needs no closure, so
// its bundle root need not be staged. Building one service must not depend on
// every other service having been staged too.

const SHIPPING = {
    "apionly.yaml": `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
  asyncapi: asyncapi
defaults:
  openapi:
    outputName: openapi.yaml
  asyncapi:
    outputName: asyncapi.yaml
build:
  staging: build/staging
  dist: dist
channels:
  file:
    directory: build/publish
targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
  gamma:
    asyncapi:
      bundle: gamma.yaml
  overview:
    publish: false
    asyncapi:
      bundle: overview.yaml
`,
    "specs/openapi/bundles/alpha.yaml": "openapi: 3.1.1\npaths:\n  /a:\n    $ref: '../paths/A.yaml'\n",
    "specs/openapi/paths/A.yaml": "get: {}\n",
    "specs/asyncapi/gamma.yaml": "asyncapi: 3.1.0\nchannels: {}\n",
    "specs/openapi/bundles/alpha.bundle.properties": "version=1.0.0\n",
    "specs/asyncapi/gamma.bundle.properties": "version=1.0.0\n",
    // No bundle root for overview: like an aggregate, only a build of overview
    // itself puts one in the staged tree.
};

function shipped(dir, target, kind, version) {
    const dist = path.join(dir, "dist", target);
    fs.mkdirSync(dist, { recursive: true });
    fs.writeFileSync(path.join(dist, `${kind}.yaml`), `${kind}: x\ninfo:\n  title: X\n  version: ${version}\n`);
}

test("pack and publish of one target need only that target staged", async () => {
    const dir = library(SHIPPING);
    const config = loadFrom(dir);
    // What `build --target alpha` stages: the OpenAPI tree, and not the AsyncAPI one.
    prepare(config, { kinds: ["openapi"] });
    shipped(dir, "alpha", "openapi", "1.0.0");

    await run(["pack", "--target", "alpha", "-C", dir]);
    await run(["publish", "--target", "alpha", "-C", dir]);

    assert.ok(fs.existsSync(path.join(dir, "build", "packages", "alpha-1.0.0.tgz")));
    const manifest = JSON.parse(
        fs.readFileSync(path.join(dir, "build", "publish", "alpha", "1.0.0", "manifest.json"), "utf8"));
    assert.strictEqual(manifest.closureSha256, forTargets(config, ["openapi"]).get("alpha").sha256);
});

test("publish of every target hashes no target it does not publish", async () => {
    const dir = library(SHIPPING);
    prepare(loadFrom(dir));
    shipped(dir, "alpha", "openapi", "1.0.0");
    shipped(dir, "gamma", "asyncapi", "1.0.0");

    const { printed } = await run(["publish", "-C", dir]);

    assert.ok(printed.includes("\nPublished 2 artifact(s)."));
});

test("publish --pre-release ships the declared version with the pre-release appended", async () => {
    const dir = library();
    prepare(loadFrom(dir));
    built(dir, "alpha", "1.0.0-rc.1");

    await run(["publish", "--pre-release", "rc.1", "-C", dir]);

    assert.ok(fs.existsSync(path.join(dir, "build", "publish", "alpha", "1.0.0-rc.1", "alpha-1.0.0-rc.1.tgz")));
});

test("publish refuses a target with no version file before shipping any target", async () => {
    const dir = library(SHIPPING);
    fs.rmSync(path.join(dir, "specs/asyncapi/gamma.bundle.properties"));
    prepare(loadFrom(dir));
    shipped(dir, "alpha", "openapi", "1.0.0");
    shipped(dir, "gamma", "asyncapi", "1.0.0");

    await assert.rejects(() => main(["publish", "-C", dir]), /target 'gamma': no version file/);
    assert.ok(!fs.existsSync(path.join(dir, "build", "publish")));
});
