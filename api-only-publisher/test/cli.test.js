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
    fs.mkdirSync(path.join(dir, "specs", "openapi", "bundles"), { recursive: true });
    fs.writeFileSync(path.join(dir, "specs", "openapi", "bundles", "example.yaml"), "openapi: 3.1.1\npaths: {}\n");
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
    fs.mkdirSync(path.join(dir, "specs", "openapi", "bundles"), { recursive: true });
    fs.writeFileSync(path.join(dir, "specs", "openapi", "bundles", "example.yaml"), "openapi: 3.1.1\npaths: {}\n");
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
        await assert.rejects(() => main(["lint", "-C", dir]), /lint failed for 1 document\(s\): example \(openapi\)/);
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

/**
 * A terminal played by a script: each time the CLI asks a question on `output`, the next
 * answer is typed into `input`. The CLI reads it through node:readline, as it does from
 * a real terminal.
 */
function terminal(...answers) {
    const { PassThrough } = require("node:stream");
    const input = new PassThrough();
    const output = new PassThrough();
    const shown = [];
    output.on("data", (chunk) => {
        const text = chunk.toString();
        shown.push(text);
        if (/(: |\] )$/.test(text)) input.write(`${answers.length > 0 ? answers.shift() : ""}\n`);
    });
    return { io: { interactive: true, input, output }, shown };
}

async function runWith(argv, io) {
    const printed = [];
    const previousLog = console.log;
    console.log = (message) => printed.push(String(message));
    try {
        return { code: await main(argv, io), printed };
    } finally {
        console.log = previousLog;
    }
}

test("init without a terminal takes flags for what they name and defaults for the rest", async () => {
    const parent = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    const { code, printed } = await runWith(
        ["init", "lib", "-C", parent, "--asyncapi", "--target", "orders", "--title", "Orders API"],
        { interactive: false });

    assert.strictEqual(code, 0);
    const config = fs.readFileSync(path.join(parent, "lib", "apionly.yaml"), "utf8");
    assert.match(config, /^ {2}orders:\n {4}asyncapi:\n {6}bundle: bundles\/orders_asyncapi_structure\.yaml$/m);
    assert.ok(!/openapi/.test(config));
    assert.ok(printed.includes("  created    specs/asyncapi/bundles/orders_asyncapi_structure.yaml"));
});

test("init at a terminal asks each question, takes the answers, and writes them", async () => {
    const parent = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    const { io, shown } = terminal("both", "orders", "Orders API");
    const { code } = await runWith(["init", "lib", "-C", parent], io);

    assert.strictEqual(code, 0);
    assert.ok(shown.some((text) => text.startsWith("Kinds of document")));
    assert.ok(shown.some((text) => text.startsWith("Broker host")));
    const lib = path.join(parent, "lib");
    assert.ok(fs.existsSync(path.join(lib, "specs/openapi/bundles/orders_openapi_structure.yaml")));
    assert.ok(fs.existsSync(path.join(lib, "specs/asyncapi/bundles/orders_asyncapi_structure.yaml")));
    assert.match(fs.readFileSync(path.join(lib, "specs/openapi/shared/info.yaml"), "utf8"), /^title: Orders API$/m);
});

test("init --yes at a terminal asks nothing", async () => {
    const parent = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    const { io, shown } = terminal();
    const { code } = await runWith(["init", "lib", "-C", parent, "--yes"], io);

    assert.strictEqual(code, 0);
    assert.deepStrictEqual(shown, []);
    assert.ok(fs.existsSync(path.join(parent, "lib", "specs/openapi/bundles/example-service_openapi_structure.yaml")));
});

test("init --force at a terminal lists what differs and asks once before overwriting", async () => {
    const parent = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    await runWith(["init", "lib", "-C", parent], { interactive: false });
    const config = path.join(parent, "lib", "apionly.yaml");
    fs.writeFileSync(config, "# edited by hand\n");

    const declined = terminal("", "", "", "", "", "", "", "", "n");
    await runWith(["init", "lib", "-C", parent, "--force"], declined.io);
    assert.strictEqual(fs.readFileSync(config, "utf8"), "# edited by hand\n");
    assert.ok(declined.shown.some((text) => text.includes("apionly.yaml")));
    assert.ok(declined.shown.some((text) => text.startsWith("Overwrite these 1 file(s)? [y/N] ")));

    const accepted = terminal("", "", "", "", "", "", "", "", "y");
    const { printed } = await runWith(["init", "lib", "-C", parent, "--force"], accepted.io);
    assert.notStrictEqual(fs.readFileSync(config, "utf8"), "# edited by hand\n");
    assert.ok(printed.includes("  overwrote  apionly.yaml"));
});

test("init adds a missing kind's configuration to an existing library, without --force", async () => {
    const parent = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    await runWith(["init", "lib", "-C", parent, "--openapi", "--target", "orders"], { interactive: false });

    const { code, printed } = await runWith(
        ["init", "lib", "-C", parent, "--openapi", "--asyncapi", "--target", "orders"], { interactive: false });

    assert.strictEqual(code, 0);
    assert.ok(printed.some((line) => line.startsWith("  updated") && line.includes("apionly.yaml") &&
        line.includes("sources.asyncapi") && line.includes("targets.orders.asyncapi")));
    const config = fs.readFileSync(path.join(parent, "lib", "apionly.yaml"), "utf8");
    assert.match(config, /asyncapi: asyncapi/);
    assert.match(config, /bundle: bundles\/orders_asyncapi_structure\.yaml/);
});

test("init --force at a terminal lists only what still differs after the additive update, not what it completed", async () => {
    const parent = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    const values = [
        "--title", "Orders API", "--contract-version", "0.1.0", "--contact-name", "Orders Team",
        "--contact-url", "https://orders.example.com", "--license", "MIT",
        "--server-url", "https://api.orders.example.com",
    ];
    await runWith(["init", "lib", "-C", parent, "--openapi", "--target", "orders", ...values], { interactive: false });
    const config = path.join(parent, "lib", "apionly.yaml");
    fs.writeFileSync(config, `${fs.readFileSync(config, "utf8")}\n# a hand-added note, kept by the additive update\n`);

    // Adding --asyncapi both completes the file (additively) and leaves the hand-added
    // note as an unrelated difference from a from-scratch scaffold; --force is asked
    // about that residual difference only, not about what was just completed. Every
    // other value is given as a flag and repeated unchanged, so the ExamplesV1.yaml path
    // fragment is the only other file this run touches, and the only thing asked at this
    // terminal is the overwrite confirmation, which is what "n" answers.
    const declined = terminal("n");
    const { printed } = await runWith(
        ["init", "lib", "-C", parent, "--openapi", "--asyncapi", "--target", "orders", "--force",
            ...values, "--broker-host", "kafka:9092"],
        declined.io);

    assert.ok(declined.shown.some((text) => text.includes("apionly.yaml") && text.includes("differ from the scaffold")));
    assert.ok(declined.shown.some((text) => /^Overwrite these \d+ file\(s\)\? \[y\/N\] $/.test(text)));
    assert.ok(printed.some((line) => line.startsWith("  updated") && line.includes("apionly.yaml")));
    assert.match(fs.readFileSync(config, "utf8"), /# a hand-added note, kept by the additive update/);
    assert.match(fs.readFileSync(config, "utf8"), /asyncapi: asyncapi/);
});

test("init refuses more than one target, and an invalid value given as a flag, writing nothing", async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-cli-"));
    await assert.rejects(runWith(["init", "-C", dir, "--target", "a", "--target", "b"], { interactive: false }),
        /init scaffolds one target; --target was given 2 times/);
    await assert.rejects(runWith(["init", "-C", dir, "--target", "Orders"], { interactive: false }),
        /--target 'Orders' is not a target name/);
    assert.deepStrictEqual(fs.readdirSync(dir), []);
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

// ------------------------------------------------------ lint, across the library
//
// A linter checks documents, and a fragment reaches a document only through a
// $ref. So lint also looks for fragments no target reaches: nothing else would
// ever lint them.

const LINTED = {
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
targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
  beta:
    publish: false
    openapi:
      bundle: bundles/beta.yaml
`,
    "specs/openapi/bundles/alpha.yaml": "openapi: 3.1.1\npaths:\n  /a:\n    $ref: '../paths/A.yaml'\n",
    // Reached only by beta, which is never published but is linted all the same.
    "specs/openapi/bundles/beta.yaml": "openapi: 3.1.1\npaths:\n  /b:\n    $ref: '../paths/B.yaml'\n",
    "specs/openapi/paths/A.yaml": "get: {}\n",
    "specs/openapi/paths/B.yaml": "get: {}\n",
    "dist/alpha/openapi.yaml": "openapi: 3.1.1\ninfo:\n  title: A\n  version: 1.0.0\npaths: {}\n",
    "dist/beta/openapi.yaml": "openapi: 3.1.1\ninfo:\n  title: B\n  version: 0.0.0\npaths: {}\n",
};

const ORPHAN = { "specs/openapi/components/Orphan.yaml": "type: string\n" };

const FAILS_FOR_ALPHA = [
    "#!/bin/sh",
    "case \"$*\" in *dist/alpha/*) echo 'alpha is not valid'; exit 1;; esac",
    "",
].join("\n");

async function silenced(action) {
    const previous = console.error;
    console.error = () => {};
    try {
        return await action();
    } finally {
        console.error = previous;
    }
}

function withLintMode(mode) {
    return { ...LINTED, "apionly.yaml": LINTED["apionly.yaml"] + `lint:\n  unreferenced: ${mode}\n` };
}

test("lint lints every document even when one fails, and names every failure", async () => {
    const dir = library(LINTED);

    await assert.rejects(
        () => silenced(() => withToolchain(dir, FAILS_FOR_ALPHA, () => main(["lint", "-q", "-C", dir]))),
        /lint failed for 1 document\(s\): alpha \(openapi\)/);

    assert.match(fs.readFileSync(path.join(dir, "build/reports/lint/alpha/openapi.txt"), "utf8"), /alpha is not valid/);
    assert.ok(fs.existsSync(path.join(dir, "build/reports/lint/beta/openapi.txt")), "beta is linted after alpha fails");
});

test("lint fails on a fragment no target reaches, and lists it in a report", async () => {
    const dir = library({ ...LINTED, ...ORPHAN });

    await assert.rejects(
        () => silenced(() => withToolchain(dir, FAKE_TOOLCHAIN, () => main(["lint", "-q", "-C", dir]))),
        /1 fragment\(s\) not reachable from any target, so nothing lints them:\n {2}openapi\/components\/Orphan\.yaml/);
    assert.strictEqual(
        fs.readFileSync(path.join(dir, "build/reports/lint/unreferenced.txt"), "utf8"),
        "openapi/components/Orphan.yaml\n");
});

test("lint passes a library whose every fragment some target reaches, with an empty report", async () => {
    const dir = library(LINTED);

    const { code } = await withToolchain(dir, FAKE_TOOLCHAIN, () => run(["lint", "-q", "-C", dir]));

    assert.strictEqual(code, 0);
    assert.strictEqual(fs.readFileSync(path.join(dir, "build/reports/lint/unreferenced.txt"), "utf8"), "");
});

test("lint.unreferenced: warn reports a fragment no target reaches without failing", async () => {
    const dir = library({ ...withLintMode("warn"), ...ORPHAN });

    const { code, printed } = await withToolchain(dir, FAKE_TOOLCHAIN, () => run(["lint", "-C", dir]));

    assert.strictEqual(code, 0);
    assert.ok(printed.some((line) => /not reachable from any target/.test(line) && /Orphan\.yaml/.test(line)));
});

test("lint.unreferenced: off does not look for unreferenced fragments", async () => {
    const dir = library({ ...withLintMode("off"), ...ORPHAN });

    const { code } = await withToolchain(dir, FAKE_TOOLCHAIN, () => run(["lint", "-q", "-C", dir]));

    assert.strictEqual(code, 0);
    assert.ok(!fs.existsSync(path.join(dir, "build/reports/lint/unreferenced.txt")));
});

test("lint --target does not look for unreferenced fragments: they belong to no target", async () => {
    const dir = library({ ...LINTED, ...ORPHAN });

    const { code } = await withToolchain(dir, FAKE_TOOLCHAIN, () => run(["lint", "--target", "alpha", "-q", "-C", dir]));

    assert.strictEqual(code, 0);
    assert.ok(!fs.existsSync(path.join(dir, "build/reports/lint/unreferenced.txt")));
});
