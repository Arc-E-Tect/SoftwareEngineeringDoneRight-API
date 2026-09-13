"use strict";

// Covers the parts of the pipeline that touch the filesystem and the git history
// rather than just compute: staging, closures over a real tree, packing, and the
// two channels that need no network.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { load } = require("../src/config");
const { forTargets, ClosureError } = require("../src/closure");
const { prepare } = require("../src/pipeline");
const { pack, manifest } = require("../src/pack");
const { publish } = require("../src/channels");
const { stampFile, VersionError } = require("../src/version");

const CONFIG = `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
  asyncapi: asyncapi
defaults:
  openapi:
    outputName: openapi.yaml
  asyncapi:
    outputName: asyncapi.yaml
  placeholders:
    strict: true
build:
  staging: build/staging
  dist: dist
toolchain:
  redocly: "@redocly/cli@2.52.0"
  asyncapi: "@asyncapi/cli@6.0.2"
channels:
  file:
    directory: build/publish
  maven:
    groupId: com.example.contracts
targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
  beta:
    publish: false
    openapi:
      bundle: bundles/beta.yaml
`;

// A miniature library whose alpha target reaches a shared schema, and whose beta
// target does not -- which is what makes "which targets changed" a real question.
const FILES = {
    "apionly.yaml": CONFIG,
    "specs/openapi/bundles/alpha.yaml":
        "openapi: 3.1.1\ninfo:\n  $ref: '../shared/info.yaml'\npaths:\n  /a:\n    $ref: '../paths/A.yaml'\n",
    "specs/openapi/bundles/beta.yaml":
        "openapi: 3.1.1\ninfo:\n  $ref: '../shared/info.yaml'\npaths: {}\n",
    "specs/openapi/paths/A.yaml":
        "get:\n  x:\n    $ref: '../components/common/Shared.yaml'\n",
    "specs/openapi/components/common/Shared.yaml": "type: string\n",
    "specs/openapi/shared/info.yaml": "title: Example\nversion: 0.0.0\ndescription: |\n  {{intro}}\n",
    "specs/openapi/shared/intro.md": "Some shared prose.\n",
};

function library() {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-pipeline-"));
    for (const [rel, content] of Object.entries(FILES)) {
        const file = path.join(dir, rel);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, content);
    }
    return load(path.join(dir, "apionly.yaml"));
}

// --------------------------------------------------------------- staging

test("staging mirrors the whole source root and substitutes in place", () => {
    const config = library();
    prepare(config, { kinds: ["openapi"] });

    const staged = path.join(config.stagingRoot("openapi"), "openapi/shared/info.yaml");
    assert.match(fs.readFileSync(staged, "utf8"), /Some shared prose/);
    // The hand-authored tree is never written to.
    assert.match(fs.readFileSync(path.join(config.sourceRoot(), "openapi/shared/info.yaml"), "utf8"), /\{\{intro\}\}/);
});

test("staging is rebuilt from scratch, so a deleted fragment does not linger", () => {
    const config = library();
    prepare(config, { kinds: ["openapi"] });
    const stray = path.join(config.stagingRoot("openapi"), "openapi/stray.yaml");
    fs.writeFileSync(stray, "left over\n");

    prepare(config, { kinds: ["openapi"] });

    assert.ok(!fs.existsSync(stray), "staging must be wiped, not merged into");
});

// --------------------------------------------------------------- closures

test("a closure reaches transitively, and stops at what is actually referenced", () => {
    const config = library();
    prepare(config, { kinds: ["openapi"] });
    const closures = forTargets(config, ["openapi"]);

    const alpha = closures.get("alpha").files.map((f) => path.basename(f)).sort();
    assert.deepStrictEqual(alpha, ["A.yaml", "Shared.yaml", "alpha.yaml", "info.yaml"]);

    const beta = closures.get("beta").files.map((f) => path.basename(f)).sort();
    assert.deepStrictEqual(beta, ["beta.yaml", "info.yaml"]);
});

test("a closure hash changes when shared prose changes", () => {
    // The closure is computed over the substituted tree precisely so that editing
    // a Markdown snippet counts as changing the published document.
    const config = library();
    prepare(config, { kinds: ["openapi"] });
    const before = forTargets(config, ["openapi"]).get("alpha").sha256;

    fs.writeFileSync(path.join(config.sourceRoot(), "openapi/shared/intro.md"), "Different prose.\n");
    prepare(config, { kinds: ["openapi"] });
    const after = forTargets(config, ["openapi"]).get("alpha").sha256;

    assert.notStrictEqual(before, after);
});

test("a change to a fragment only one target reaches moves only that target's hash", () => {
    const config = library();
    prepare(config, { kinds: ["openapi"] });
    const before = forTargets(config, ["openapi"]);

    fs.writeFileSync(path.join(config.sourceRoot(), "openapi/components/common/Shared.yaml"), "type: integer\n");
    prepare(config, { kinds: ["openapi"] });
    const after = forTargets(config, ["openapi"]);

    assert.notStrictEqual(before.get("alpha").sha256, after.get("alpha").sha256);
    assert.strictEqual(before.get("beta").sha256, after.get("beta").sha256);
});

test("a closure restricted to some targets never reads the others' bundle roots", () => {
    // pack and publish hash only what they ship, so a build of one target must not
    // depend on every other target having been staged.
    const config = library();
    prepare(config, { kinds: ["openapi"] });
    fs.rmSync(config.bundlePath("beta", "openapi"));

    assert.throws(() => forTargets(config, ["openapi"]), (e) => e instanceof ClosureError);
    assert.deepStrictEqual([...forTargets(config, ["openapi"], ["alpha"]).keys()], ["alpha"]);
});

// --------------------------------------------------------------- stamping

test("stamping a file rewrites info.version and nothing else", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-stamp-"));
    const file = path.join(dir, "openapi.yaml");
    const source = "openapi: 3.1.1\ninfo:\n  title: X\n  version: 0.0.0\npaths: {}\n";
    fs.writeFileSync(file, source);

    stampFile(file, "2.3.1");

    assert.strictEqual(fs.readFileSync(file, "utf8"), source.replace("0.0.0", "2.3.1"));
});

test("stamping refuses a document it cannot stamp, and leaves it alone", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-stamp-"));
    const file = path.join(dir, "openapi.yaml");
    fs.writeFileSync(file, "openapi: 3.1.1\npaths: {}\n");

    assert.throws(() => stampFile(file, "1.0.0"), (e) => e instanceof VersionError);
    assert.strictEqual(fs.readFileSync(file, "utf8"), "openapi: 3.1.1\npaths: {}\n");
});

// --------------------------------------------------------------- packing

function built(config, target, version) {
    const dir = config.distDir(target);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, "openapi.yaml"),
        `openapi: 3.1.1\ninfo:\n  title: X\n  version: ${version}\npaths: {}\n`);
    return dir;
}

test("packing writes a manifest beside the documents and archives both", () => {
    const config = library();
    built(config, "alpha", "1.2.3");

    const result = pack(config, "alpha", {
        version: "1.2.3", closureSha256: "a".repeat(64),
        outDir: path.join(config.root, "build/packages"),
    });

    assert.ok(fs.existsSync(result.archive));
    assert.strictEqual(result.manifest.target, "alpha");
    assert.strictEqual(result.manifest.closureSha256, "a".repeat(64));

    const entries = execFileSync("tar", ["-tzf", result.archive], { encoding: "utf8" }).trim().split("\n").sort();
    assert.deepStrictEqual(entries, ["manifest.json", "openapi.yaml"]);
});

test("packing refuses a document stamped with a different version", () => {
    // Nothing downstream would catch this: every later check trusts the manifest.
    const config = library();
    built(config, "alpha", "9.9.9");

    assert.throws(
        () => pack(config, "alpha", { version: "1.2.3", closureSha256: "x", outDir: path.join(config.root, "out") }),
        /declares info\.version '9\.9\.9' but is being packed as '1\.2\.3'/
    );
});

test("packing refuses a target that was never built", () => {
    const config = library();
    assert.throws(
        () => pack(config, "alpha", { version: "1.0.0", closureSha256: "x", outDir: path.join(config.root, "out") }),
        /nothing built at/
    );
});

test("a manifest records a sha256 per document, sorted by name", () => {
    const config = library();
    const dir = built(config, "alpha", "1.0.0");
    fs.writeFileSync(path.join(dir, "asyncapi.yaml"), "asyncapi: 3.1.0\n");

    const data = manifest(config, "alpha", {
        version: "1.0.0",
        files: [path.join(dir, "openapi.yaml"), path.join(dir, "asyncapi.yaml")],
        closureSha256: "x",
    });

    assert.deepStrictEqual(data.files.map((f) => f.path), ["asyncapi.yaml", "openapi.yaml"]);
    for (const entry of data.files) assert.match(entry.sha256, /^[0-9a-f]{64}$/);
});

// --------------------------------------------------------------- channels

test("the file channel lays out target and version directories", () => {
    const config = library();
    built(config, "alpha", "1.0.0");
    const result = pack(config, "alpha", {
        version: "1.0.0", closureSha256: "x", outDir: path.join(config.root, "build/packages"),
    });

    const published = publish(result.archive, result.manifest, "file",
        { ...config.channels.file, baseDir: config.root });

    assert.ok(published.location.endsWith(path.join("alpha", "1.0.0", "alpha-1.0.0.tgz")));
    assert.ok(fs.existsSync(path.join(path.dirname(published.location), "manifest.json")));
});

test("the file channel keeps a target's other versions, unless asked to clean them", () => {
    const config = library();
    const publishAs = (version, options) => {
        built(config, "alpha", version);
        const result = pack(config, "alpha", {
            version, closureSha256: "x", outDir: path.join(config.root, "build/packages"),
        });
        return publish(result.archive, result.manifest, "file", { ...config.channels.file, ...options, baseDir: config.root });
    };
    const versions = () => fs.readdirSync(path.join(config.root, "build/publish/alpha")).sort();

    publishAs("1.0.0", {});
    publishAs("1.1.0", {});
    assert.deepStrictEqual(versions(), ["1.0.0", "1.1.0"]);

    publishAs("1.2.0", { clean: true });
    assert.deepStrictEqual(versions(), ["1.2.0"]);
});

test("the maven channel writes a layout a resolver can read", () => {
    const config = library();
    built(config, "alpha", "1.0.0");
    const result = pack(config, "alpha", {
        version: "1.0.0", closureSha256: "x", outDir: path.join(config.root, "build/packages"),
    });

    const published = publish(result.archive, result.manifest, "maven", {
        groupId: "com.example.contracts",
        repository: path.join(config.root, "m2"),
        baseDir: config.root,
    });

    const base = path.join(config.root, "m2", "com", "example", "contracts", "alpha", "1.0.0");
    assert.ok(fs.existsSync(path.join(base, "alpha-1.0.0.tgz")));
    assert.match(fs.readFileSync(path.join(base, "alpha-1.0.0.pom"), "utf8"), /<artifactId>alpha<\/artifactId>/);
    assert.strictEqual(published.coordinates, "com.example.contracts:alpha:1.0.0@tgz");
});

test("the npm channel packs a scoped, public package without publishing it", () => {
    const config = library();
    built(config, "alpha", "1.0.0");
    const result = pack(config, "alpha", {
        version: "1.0.0", closureSha256: "x", outDir: path.join(config.root, "build/packages"),
    });

    const published = publish(result.archive, result.manifest, "npm", {
        scope: "@example", publish: false, baseDir: config.root, directory: "build/packages/npm",
    });

    assert.strictEqual(published.name, "@example/alpha");
    assert.strictEqual(published.distTag, "latest");
    assert.ok(fs.existsSync(published.location));
});

test("a pre-release goes to the next dist-tag, never latest", () => {
    const config = library();
    built(config, "alpha", "1.1.0-rc.1");
    const result = pack(config, "alpha", {
        version: "1.1.0-rc.1", closureSha256: "x", outDir: path.join(config.root, "build/packages"),
    });

    const published = publish(result.archive, result.manifest, "npm", {
        scope: "@example", publish: false, baseDir: config.root, directory: "build/packages/npm",
    });

    assert.strictEqual(published.distTag, "next");
});

// --------------------------------------------------- channel error paths

test("an unknown channel names the ones that exist", () => {
    assert.throws(() => publish("/tmp/x.tgz", { target: "a", version: "1.0.0" }, "smoke-signal", {}),
        /unknown channel 'smoke-signal'.*file, maven, npm, github-release/s);
});

test("the maven channel refuses a remote deploy with no credential, naming the variable", () => {
    // The message has to name the variable, because the person reading it set the
    // configuration and the person who must set the secret may be someone else.
    assert.throws(
        () => publish("/tmp/x.tgz", { target: "a", version: "1.0.0" }, "maven", {
            groupId: "com.example", repository: "https://maven.example.invalid/repo", tokenEnv: "SOME_TOKEN",
        }),
        /no credential in \$SOME_TOKEN.*channels\.maven\.tokenEnv/s
    );
});

test("the github-release channel requires a repository", () => {
    assert.throws(() => publish("/tmp/x.tgz", { target: "a", version: "1.0.0" }, "github-release", {}),
        /requires a repository/);
});
