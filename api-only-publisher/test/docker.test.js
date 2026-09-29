"use strict";

// The Docker image's own files, checked without Docker: the toolchain it installs
// is the one `init` pins, its labels say so, and its entrypoint hands the
// Publisher's commands to the Publisher and everything else to the shell.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { TOOLCHAIN_PIN } = require("../src/init-config");
const { TOOL_OF_KIND, LABEL_PREFIX } = require("../src/toolchain");

const ROOT = path.join(__dirname, "..");
const DOCKERFILE = fs.readFileSync(path.join(ROOT, "Dockerfile"), "utf8");

test("the image installs exactly the toolchain init pins, from a lockfile that agrees", () => {
    const manifest = JSON.parse(fs.readFileSync(path.join(ROOT, "docker", "toolchain", "package.json"), "utf8"));
    const lock = JSON.parse(fs.readFileSync(path.join(ROOT, "docker", "toolchain", "package-lock.json"), "utf8"));
    const pinned = Object.values(TOOLCHAIN_PIN).sort();
    const declared = Object.entries(manifest.dependencies).map(([name, version]) => `${name}@${version}`).sort();
    assert.deepStrictEqual(declared, pinned);
    for (const [name, version] of Object.entries(manifest.dependencies)) {
        assert.strictEqual(lock.packages[`node_modules/${name}`].version, version, name);
    }
});

test("the image's toolchain labels name the pins init writes", () => {
    for (const [kind, key] of Object.entries(TOOL_OF_KIND)) {
        assert.ok(DOCKERFILE.includes(`${LABEL_PREFIX}${key}="${TOOLCHAIN_PIN[kind]}"`), `${LABEL_PREFIX}${key}`);
    }
});

test("the image refuses downloads and names where its toolchain is", () => {
    assert.match(DOCKERFILE, /API_ONLY_PUBLISHER_TOOLCHAIN_DOWNLOAD=never/);
    assert.match(DOCKERFILE, /API_ONLY_PUBLISHER_TOOLCHAIN_DIR=\/opt\/api-only-publisher\/toolchain/);
});

test("the base image is pinned by digest", () => {
    assert.match(DOCKERFILE, /^ARG BASE=node@sha256:[0-9a-f]{64}$/m);
});

// The commands the help lists, which the entrypoint must hand to the Publisher.
function commands() {
    const cli = fs.readFileSync(path.join(ROOT, "src", "cli.js"), "utf8");
    return [...new Set([...cli.matchAll(/^ {2}api-only-publisher ([a-z]+)/gm)].map((m) => m[1]))].sort();
}

function entrypoint(args) {
    const bin = fs.mkdtempSync(path.join(os.tmpdir(), "aop-entrypoint-"));
    fs.writeFileSync(path.join(bin, "api-only-publisher"), "#!/bin/sh\necho \"publisher $*\"\n");
    fs.chmodSync(path.join(bin, "api-only-publisher"), 0o755);
    return execFileSync("sh", [path.join(ROOT, "docker", "entrypoint.sh"), ...args],
        { encoding: "utf8", env: { ...process.env, PATH: `${bin}:${process.env.PATH}` } }).trim();
}

test("the entrypoint runs the Publisher for each of its commands", () => {
    const listed = commands();
    assert.deepStrictEqual(listed,
        ["build", "changed", "closure", "config", "init", "lint", "pack", "publish", "split", "targets"]);
    for (const command of listed) {
        assert.strictEqual(entrypoint([command, "--target", "a b"]), `publisher ${command} --target a b`);
    }
});

test("the entrypoint runs the Publisher for a flag, or nothing at all", () => {
    assert.strictEqual(entrypoint(["--version"]), "publisher --version");
    assert.strictEqual(entrypoint(["-C", "/work", "build"]), "publisher -C /work build");
    assert.strictEqual(entrypoint([]), "publisher");
});

test("the entrypoint runs anything else as given, as a CI runner's shell", () => {
    assert.strictEqual(entrypoint(["sh", "-c", "echo shell $0", "x"]), "shell x");
    assert.strictEqual(entrypoint(["echo", "build"]), "build");
});

// The end-to-end workflow runs the reference pipelines' steps, not look-alikes: a
// change to one without the other would leave the documented pipeline unexercised.
const YAML = require("yaml");
const REPO = path.join(ROOT, "..");
const yamlOf = (file) => YAML.parse(fs.readFileSync(file, "utf8"));
const REFERENCE = yamlOf(path.join(ROOT, "pipelines", "github-actions.yml"));
const CONTAINER_JOB = yamlOf(path.join(ROOT, "pipelines", "github-actions-container-job.yml"));
const E2E = yamlOf(path.join(REPO, ".github", "workflows", "api-only-publisher-image-e2e.yml"));

// A job's steps as the pipeline defines them, less the one way the library arrives.
function steps(job) {
    return job.steps.filter((step) => !["Checkout", "Receive the library"].includes(step.name));
}

test("the end-to-end workflow packages exactly as the reference pipeline does", () => {
    assert.deepStrictEqual(steps(E2E.jobs.package), steps(REFERENCE.jobs.package));
});

test("the end-to-end workflow's container job packages exactly as the reference one does", () => {
    assert.deepStrictEqual(steps(E2E.jobs["package-in-container"]).map((s) => s.run || s.with),
        steps(CONTAINER_JOB.package).map((s) => s.run || s.with).map((v, i) =>
            // The artifact name differs, so the compare job can fetch both.
            (i === 1 ? { ...v, name: "api-bundles-container" } : v)));
    assert.deepStrictEqual(E2E.jobs["package-in-container"].container.options, CONTAINER_JOB.package.container.options);
});

test("the end-to-end workflow publishes exactly as the reference pipeline does", () => {
    assert.deepStrictEqual(steps(E2E.jobs.publish), steps(REFERENCE.jobs.publish));
    const { RELEASE_TAG: e2eTag, ...e2eEnv } = E2E.jobs.publish.env;
    const { RELEASE_TAG: referenceTag, ...referenceEnv } = REFERENCE.jobs.publish.env;
    assert.deepStrictEqual(e2eEnv, referenceEnv);
    assert.ok(e2eTag && referenceTag);
    assert.deepStrictEqual(E2E.jobs.publish.permissions, REFERENCE.jobs.publish.permissions);
});

test("the end-to-end workflow runs only when dispatched", () => {
    assert.deepStrictEqual(Object.keys(E2E.on), ["workflow_dispatch"]);
});
