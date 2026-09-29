"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { parseSpec, installed, resolve, requireTools, ToolchainError, DIR_ENV, DOWNLOAD_ENV } = require("../src/toolchain");
const { BuildError } = require("../src/pipeline");
const { toolchainDir, IMAGE_TOOLS } = require("./toolchain-dir");

test("parseSpec splits a scoped package from its version", () => {
    assert.deepStrictEqual(parseSpec("@redocly/cli@2.52.0"), { name: "@redocly/cli", version: "2.52.0" });
    assert.deepStrictEqual(parseSpec("some-cli@1.0.0"), { name: "some-cli", version: "1.0.0" });
    assert.deepStrictEqual(parseSpec("@redocly/cli"), { name: "@redocly/cli", version: null });
    assert.deepStrictEqual(parseSpec("some-cli"), { name: "some-cli", version: null });
});

test("without the variables, a tool runs through npx exactly as before", () => {
    assert.deepStrictEqual(resolve("redocly", "@redocly/cli@2.52.0", {}),
        { command: "npx", args: ["--yes", "@redocly/cli@2.52.0"] });
});

test("an installed tool at the pinned version runs from its own bin, not npx", () => {
    const dir = toolchainDir(IMAGE_TOOLS);
    const env = { [DIR_ENV]: dir, [DOWNLOAD_ENV]: "never" };
    assert.deepStrictEqual(resolve("redocly", "@redocly/cli@2.52.0", env),
        { command: path.join(dir, "node_modules", "@redocly", "cli", "bin", "cli.js"), args: [] });
    assert.deepStrictEqual(resolve("asyncapi", "@asyncapi/cli@6.0.2", env),
        { command: path.join(dir, "node_modules", "@asyncapi", "cli", "bin", "run_bin"), args: [] });
});

test("a tool whose bin is a single string runs from it", () => {
    const dir = toolchainDir([{ name: "some-cli", version: "1.0.0", bin: "bin/some.js" }]);
    assert.deepStrictEqual(resolve("some", "some-cli@1.0.0", { [DIR_ENV]: dir }),
        { command: path.join(dir, "node_modules", "some-cli", "bin", "some.js"), args: [] });
});

test("a tool with several bins, none named after it, cannot be run from its install", () => {
    const dir = toolchainDir([{ name: "some-cli", version: "1.0.0", bin: { a: "bin/a.js", b: "bin/b.js" } }]);
    assert.throws(() => resolve("some", "some-cli@1.0.0", { [DIR_ENV]: dir }),
        (error) => error instanceof ToolchainError && /some-cli@1\.0\.0 declares several bins \(a, b\)/.test(error.message));
});

test("an installed tool at another version falls back to npx while downloads are allowed", () => {
    const dir = toolchainDir(IMAGE_TOOLS);
    for (const download of [undefined, "allow"]) {
        const env = { [DIR_ENV]: dir };
        if (download) env[DOWNLOAD_ENV] = download;
        assert.deepStrictEqual(resolve("redocly", "@redocly/cli@2.60.0", env),
            { command: "npx", args: ["--yes", "@redocly/cli@2.60.0"] });
    }
});

test("a pin the installation does not carry fails when downloads are refused, naming what it carries and the way out", () => {
    const dir = toolchainDir(IMAGE_TOOLS);
    assert.throws(() => resolve("redocly", "@redocly/cli@2.60.0", { [DIR_ENV]: dir, [DOWNLOAD_ENV]: "never" }),
        (error) => {
            assert.ok(error instanceof ToolchainError);
            assert.ok(error instanceof BuildError, "the CLI reports it as any build failure");
            assert.match(error.message, /toolchain\.redocly pins @redocly\/cli@2\.60\.0/);
            assert.match(error.message, /carries @redocly\/cli@2\.52\.0 and @asyncapi\/cli@6\.0\.2/);
            assert.match(error.message, /Set toolchain\.redocly to "@redocly\/cli@2\.52\.0" in apionly\.yaml/);
            assert.match(error.message, /com\.arc-e-tect\.api-only-publisher\.toolchain\.redocly/);
            assert.match(error.message, /API_ONLY_PUBLISHER_TOOLCHAIN_DOWNLOAD=allow/);
            return true;
        });
});

test("a pin without a version is never taken from the installation", () => {
    const dir = toolchainDir(IMAGE_TOOLS);
    assert.throws(() => resolve("redocly", "@redocly/cli", { [DIR_ENV]: dir, [DOWNLOAD_ENV]: "never" }), ToolchainError);
});

test("refusing downloads with no toolchain installed says so", () => {
    for (const env of [{ [DOWNLOAD_ENV]: "never" }, { [DOWNLOAD_ENV]: "never", [DIR_ENV]: path.join(os.tmpdir(), "aop-no-such-dir") }]) {
        assert.throws(() => resolve("asyncapi", "@asyncapi/cli@6.0.2", env),
            (error) => error instanceof ToolchainError && /carries no toolchain/.test(error.message));
    }
});

test("an unknown download mode is refused rather than guessed at", () => {
    assert.throws(() => resolve("redocly", "@redocly/cli@2.52.0", { [DOWNLOAD_ENV]: "sometimes" }),
        (error) => error instanceof ToolchainError && /must be allow or never, not "sometimes"/.test(error.message));
});

test("installed lists every tool the toolchain directory declares, at its installed version", () => {
    assert.deepStrictEqual(installed(toolchainDir(IMAGE_TOOLS)), ["@redocly/cli@2.52.0", "@asyncapi/cli@6.0.2"]);
    assert.deepStrictEqual(installed(undefined), []);
});

test("requireTools resolves every kind's tool, and fails on the first it cannot run", () => {
    const dir = toolchainDir(IMAGE_TOOLS);
    const config = { tool: (key) => ({ redocly: "@redocly/cli@2.52.0", asyncapi: "@asyncapi/cli@6.1.0" })[key] };
    const env = { [DIR_ENV]: dir, [DOWNLOAD_ENV]: "never" };
    assert.doesNotThrow(() => requireTools(config, ["openapi"], env));
    assert.throws(() => requireTools(config, ["openapi", "asyncapi"], env), /toolchain\.asyncapi pins @asyncapi\/cli@6\.1\.0/);
});
