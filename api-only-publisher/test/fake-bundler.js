"use strict";

// A stand-in for the pinned bundler, for tests that need a target's document but not
// Redocly's: it inlines every external $ref, and lints nothing. Installed as a toolchain
// directory (API_ONLY_PUBLISHER_TOOLCHAIN_DIR), so the build runs it exactly as it runs
// the real one. The real bundler is what the *.e2e.test.js files use.

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const REDOCLY_PIN = "@redocly/cli@2.52.0";

const SCRIPT = (yaml) => `#!${process.execPath}
"use strict";
const fs = require("fs");
const path = require("path");
const YAML = require(${JSON.stringify(yaml)});

function load(file) {
    return YAML.parse(fs.readFileSync(file, "utf8"));
}

function pointer(value, at) {
    for (const part of at.split("/").filter(Boolean)) value = value[part.replace(/~1/g, "/").replace(/~0/g, "~")];
    return value;
}

function inline(value, file) {
    if (Array.isArray(value)) return value.map((v) => inline(v, file));
    if (value === null || typeof value !== "object") return value;
    if (typeof value.$ref === "string" && !value.$ref.startsWith("#")) {
        const [rel, at = ""] = value.$ref.split("#");
        const target = path.resolve(path.dirname(file), rel);
        return inline(pointer(load(target), at), target);
    }
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, inline(v, file)]));
}

const args = process.argv.slice(2);
if (args[0] === "bundle") {
    const source = path.resolve(args[1]);
    const output = args[args.indexOf("--output") + 1];
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, YAML.stringify(inline(load(source), source)));
}
`;

/** A toolchain directory whose @redocly/cli is the stand-in. */
function fakeBundlerDir() {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-fake-bundler-"));
    const root = path.join(dir, "node_modules", "@redocly", "cli");
    fs.mkdirSync(path.join(root, "bin"), { recursive: true });
    const [, version] = REDOCLY_PIN.split("@").slice(1);
    fs.writeFileSync(path.join(root, "package.json"),
        JSON.stringify({ name: "@redocly/cli", version, bin: { openapi: "bin/openapi.js", redocly: "bin/cli.js" } }));
    fs.writeFileSync(path.join(root, "bin", "cli.js"), SCRIPT(require.resolve("yaml")));
    fs.chmodSync(path.join(root, "bin", "cli.js"), 0o755);
    fs.writeFileSync(path.join(dir, "package.json"), JSON.stringify({ dependencies: { "@redocly/cli": version } }));
    return dir;
}

/** Install the stand-in for the rest of this test file's process. */
function useFakeBundler() {
    process.env.API_ONLY_PUBLISHER_TOOLCHAIN_DIR = fakeBundlerDir();
}

module.exports = { fakeBundlerDir, useFakeBundler, REDOCLY_PIN };
