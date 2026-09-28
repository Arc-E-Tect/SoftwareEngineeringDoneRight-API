"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

// A toolchain directory as `npm install --prefix <dir>` leaves it: a package.json
// naming the tools, and each tool under node_modules with its bin.
function toolchainDir(packages) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-toolchain-"));
    const dependencies = {};
    for (const { name, version, bin } of packages) {
        dependencies[name] = version;
        const root = path.join(dir, "node_modules", ...name.split("/"));
        fs.mkdirSync(path.join(root, "bin"), { recursive: true });
        fs.writeFileSync(path.join(root, "package.json"), JSON.stringify({ name, version, bin }));
        const files = typeof bin === "string" ? [bin] : Object.values(bin);
        for (const file of files) {
            fs.writeFileSync(path.join(root, file), "#!/bin/sh\necho ran \"$@\"\n");
            fs.chmodSync(path.join(root, file), 0o755);
        }
    }
    fs.writeFileSync(path.join(dir, "package.json"), JSON.stringify({ dependencies }));
    return dir;
}

const IMAGE_TOOLS = [
    { name: "@redocly/cli", version: "2.52.0", bin: { openapi: "bin/openapi.js", redocly: "bin/cli.js" } },
    { name: "@asyncapi/cli", version: "6.0.2", bin: { asyncapi: "bin/run_bin" } },
];

module.exports = { toolchainDir, IMAGE_TOOLS };
