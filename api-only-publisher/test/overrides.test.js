"use strict";

// Every default a channel writes -- a description, a release title and notes, an npm
// dist-tag -- is its default only: apionly.yaml can say otherwise, with {target} and
// {version} standing for the target and the version, as tagFormat already does.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { load } = require("../src/config");
const { pack } = require("../src/pack");
const { publish, pom } = require("../src/channels");
const { nuspec } = require("../src/nuget");

function packed(version = "1.0.0") {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-over-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"),
        "schemaVersion: 1\nsources:\n  root: specs\n  openapi: openapi\ndefaults:\n  openapi:\n    outputName: openapi.yaml\n" +
        "targets:\n  orders:\n    openapi:\n      bundle: bundles/orders.yaml\n");
    const config = load(path.join(dir, "apionly.yaml"));
    fs.mkdirSync(config.distDir("orders"), { recursive: true });
    fs.writeFileSync(path.join(config.distDir("orders"), "openapi.yaml"), `openapi: 3.1.1\ninfo:\n  version: ${version}\n`);
    return { config, ...pack(config, "orders", { version, closureSha256: "c", outDir: path.join(dir, "packages") }) };
}

function standIn(name, script) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), `aop-${name}-`));
    fs.writeFileSync(path.join(dir, name), `#!/usr/bin/env node\nconst args = process.argv.slice(2);\n` +
        `require("fs").appendFileSync(process.env.STANDIN_LOG, JSON.stringify(args) + "\\n");\n${script}\n`);
    fs.chmodSync(path.join(dir, name), 0o755);
    return { dir, log: path.join(dir, "calls.log") };
}

function withPath(standin, extra, body) {
    const saved = { PATH: process.env.PATH, STANDIN_LOG: process.env.STANDIN_LOG };
    fs.writeFileSync(standin.log, "");
    Object.assign(process.env, { PATH: `${standin.dir}${path.delimiter}${process.env.PATH}`, STANDIN_LOG: standin.log }, extra);
    try {
        body();
        return fs.readFileSync(standin.log, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l));
    } finally {
        Object.assign(process.env, saved);
        for (const key of Object.keys(extra)) delete process.env[key];
    }
}

test("the Maven POM, the npm package and the .nuspec take a configured description, and default to today's", () => {
    const manifest = { target: "orders", version: "1.2.0", source: {} };
    const custom = { description: "The {target} contract, version {version}." };

    assert.match(pom("com.example", "orders", "1.2.0", "tgz"), /<description>API description documents for orders\.<\/description>/);
    assert.match(pom("com.example", "orders", "1.2.0", "tgz", "The orders contract, version 1.2.0."),
        /<description>The orders contract, version 1\.2\.0\.<\/description>/);
    assert.match(nuspec("X", manifest, { idPrefix: "X", ...custom }), /<description>The orders contract, version 1\.2\.0\.<\/description>/);

    const { config, archive, manifest: packedManifest } = packed();
    const result = publish(archive, packedManifest, "maven", { groupId: "com.example", repository: "m2", baseDir: config.root, ...custom });
    assert.match(fs.readFileSync(result.location.replace(/\.tgz$/, ".pom"), "utf8"), /The orders contract, version 1\.0\.0\./);
});

test("npm publishes a release under releaseTag and a pre-release under prereleaseTag, defaulting to latest and next", () => {
    const npm = standIn("npm", `
if (args[0] === "pack") { console.log(JSON.stringify([{ integrity: "sha512-x" }])); process.exit(0); }
if (args[0] === "view") { console.error("npm error code E404"); process.exit(1); }
process.exit(0);`);
    const tagOf = (calls) => { const p = calls.find((c) => c[0] === "publish"); return p[p.indexOf("--tag") + 1]; };

    const release = packed("1.0.0");
    assert.strictEqual(tagOf(withPath(npm, {}, () => publish(release.archive, release.manifest, "npm", {}))), "latest");
    assert.strictEqual(tagOf(withPath(npm, {}, () => publish(release.archive, release.manifest, "npm", { releaseTag: "v1-line" }))), "v1-line");

    const rc = packed("1.1.0-rc.1");
    assert.strictEqual(tagOf(withPath(npm, {}, () => publish(rc.archive, rc.manifest, "npm", {}))), "next");
    assert.strictEqual(tagOf(withPath(npm, {}, () => publish(rc.archive, rc.manifest, "npm", { prereleaseTag: "beta" }))), "beta");
});

test("npm writes the configured description into package.json", () => {
    const { config, archive, manifest } = packed();
    const out = path.join(config.root, "npm-out");
    const result = publish(archive, manifest, "npm", { publish: false, directory: out, description: "{target} at {version}" });
    const x = fs.mkdtempSync(path.join(os.tmpdir(), "aop-npmx-"));
    require("node:child_process").execFileSync("tar", ["-xzf", result.location, "-C", x]);
    assert.strictEqual(JSON.parse(fs.readFileSync(path.join(x, "package", "package.json"), "utf8")).description, "orders at 1.0.0");
});

test("a GitHub release takes a configured title and notes, and defaults to today's", () => {
    const gh = standIn("gh", `if (args[1] === "view" && !args.includes("--json")) process.exit(1); process.exit(0);`);
    const { archive, manifest } = packed();
    const createOf = (calls) => calls.find((c) => c[1] === "create");
    const arg = (call, flag) => call[call.indexOf(flag) + 1];

    const plain = createOf(withPath(gh, {}, () => publish(archive, manifest, "github-release", { repository: "o/r" })));
    assert.strictEqual(arg(plain, "--title"), "orders 1.0.0");
    assert.strictEqual(arg(plain, "--notes"), "API description documents for orders.");

    const custom = createOf(withPath(gh, {}, () => publish(archive, manifest, "github-release",
        { repository: "o/r", title: "Orders API {version}", notes: "Contract {target} {version}." })));
    assert.strictEqual(arg(custom, "--title"), "Orders API 1.0.0");
    assert.strictEqual(arg(custom, "--notes"), "Contract orders 1.0.0.");
});
