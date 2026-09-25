"use strict";

// Publishing a version the destination already has: success when it is the same bytes,
// a failure naming the version when it is not. Every remote channel, with no registry and
// no network -- HTTP stubs on localhost, and stand-in npm and gh executables on PATH.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");

const { load } = require("../src/config");
const { pack } = require("../src/pack");
const { publish, ChannelError } = require("../src/channels");
const { tgz, TarError } = require("../src/tar");

function packed(target = "orders", version = "1.0.0") {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-idem-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"),
        `schemaVersion: 1\nsources:\n  root: specs\n  openapi: openapi\ndefaults:\n  openapi:\n    outputName: openapi.yaml\n` +
        `targets:\n  ${target}:\n    openapi:\n      bundle: bundles/${target}.yaml\n`);
    const config = load(path.join(dir, "apionly.yaml"));
    fs.mkdirSync(config.distDir(target), { recursive: true });
    fs.writeFileSync(path.join(config.distDir(target), "openapi.yaml"), `openapi: 3.1.1\ninfo:\n  version: ${version}\n`);
    process.env.SOURCE_DATE_EPOCH = "1767225600";
    try {
        return { config, ...pack(config, target, { version, closureSha256: "c", outDir: path.join(dir, "packages") }) };
    } finally {
        delete process.env.SOURCE_DATE_EPOCH;
    }
}

async function serve(handler) {
    const requests = [];
    const server = http.createServer((request, response) => {
        const chunks = [];
        request.on("data", (c) => chunks.push(c));
        request.on("end", () => {
            requests.push({ method: request.method, url: request.url, headers: request.headers });
            handler(request, response, `http://127.0.0.1:${server.address().port}`);
        });
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    return { server, requests, base: `http://127.0.0.1:${server.address().port}` };
}

async function withEnv(values, body) {
    const saved = Object.fromEntries(Object.keys(values).map((k) => [k, process.env[k]]));
    Object.assign(process.env, values);
    try {
        return await body();
    } finally {
        for (const [k, v] of Object.entries(saved)) if (v === undefined) delete process.env[k]; else process.env[k] = v;
    }
}

// ------------------------------------------------------------------ tar

test("the archive writer refuses a name that is not a plain file name", () => {
    for (const name of ["", "dir/file.yaml", "..", "x".repeat(101)]) {
        assert.throws(() => tgz([{ name, data: Buffer.alloc(0) }], 0), (e) => e instanceof TarError, name);
    }
});

// ------------------------------------------------------------------ maven

test("maven: an artifact the repository already has is left alone when identical, and refused when not", async () => {
    const { archive, manifest } = packed();
    const bytes = fs.readFileSync(archive);
    await withEnv({ M2_KEY: "t" }, async () => {
        for (const [held, expectPut, expected] of [
            [null, true, null],
            [bytes, false, "same"],
            [Buffer.from("something else"), false, /already has com\.example:orders:1\.0\.0, and it is not this archive/],
        ]) {
            const { server, requests, base } = await serve((request, response) => {
                if (request.method === "GET") {
                    if (held) { response.writeHead(200); response.end(held); } else { response.writeHead(404); response.end(); }
                    return;
                }
                response.writeHead(201); response.end();
            });
            try {
                const call = publish(archive, manifest, "maven", { groupId: "com.example", repository: `${base}/repo`, tokenEnv: "M2_KEY" });
                if (expected instanceof RegExp) {
                    await assert.rejects(call, (e) => e instanceof ChannelError && expected.test(e.message));
                } else {
                    const result = await call;
                    assert.strictEqual(result.alreadyPublished === true, expected === "same");
                }
                assert.strictEqual(requests.some((r) => r.method === "PUT"), expectPut, String(held));
                assert.strictEqual(requests[0].headers.authorization, "Bearer t");
            } finally {
                server.close();
            }
        }
    });
});

test("maven: a repository that cannot be read is reported rather than overwritten", async () => {
    const { archive, manifest } = packed();
    await withEnv({ M2_KEY: "t" }, async () => {
        for (const [status, expected] of [[500, /GET .* failed: 500/], [401, /tokenEnv/]]) {
            const { server, requests, base } = await serve((request, response) => { response.writeHead(status); response.end(); });
            try {
                await assert.rejects(
                    publish(archive, manifest, "maven", { groupId: "com.example", repository: base, tokenEnv: "M2_KEY" }),
                    (e) => e instanceof ChannelError && expected.test(e.message));
                assert.ok(!requests.some((r) => r.method === "PUT"));
            } finally {
                server.close();
            }
        }
    });
});

// ------------------------------------------------------------------ nuget

test("nuget: a version the feed already has is success when identical, and refused when not or unreadable", async () => {
    const { config, archive, manifest } = packed();
    const local = publish(archive, manifest, "nuget", { idPrefix: "Contoso", repository: "feed", baseDir: config.root });
    const bytes = fs.readFileSync(local.location);
    await withEnv({ NUGET_KEY: "k" }, async () => {
        for (const [held, contentResource, expected] of [
            [bytes, true, "same"],
            [Buffer.from("other"), true, /already has Contoso\.Orders 1\.0\.0, and it is not this package/],
            [bytes, false, /cannot be read back to compare/],
        ]) {
            const { server, requests } = await serve((request, response, base) => {
                if (request.url === "/v3/index.json") {
                    const resources = [{ "@id": `${base}/push`, "@type": "PackagePublish/2.0.0" }];
                    if (contentResource) resources.push({ "@id": `${base}/flat`, "@type": "PackageBaseAddress/3.0.0" });
                    response.writeHead(200, { "Content-Type": "application/json" });
                    response.end(JSON.stringify({ resources }));
                } else if (request.method === "PUT") {
                    response.writeHead(409); response.end();
                } else if (request.url === "/flat/contoso.orders/1.0.0/contoso.orders.1.0.0.nupkg") {
                    response.writeHead(200); response.end(held);
                } else {
                    response.writeHead(404); response.end();
                }
            });
            try {
                const index = `http://127.0.0.1:${server.address().port}/v3/index.json`;
                const call = publish(archive, manifest, "nuget", { idPrefix: "Contoso", repository: index, tokenEnv: "NUGET_KEY" });
                if (expected instanceof RegExp) {
                    await assert.rejects(call, (e) => e instanceof ChannelError && expected.test(e.message), String(expected));
                } else {
                    assert.strictEqual((await call).alreadyPublished, true);
                    assert.ok(requests.some((r) => r.url.startsWith("/flat/")));
                }
            } finally {
                server.close();
            }
        }
    });
});

test("nuget: a package the feed cannot return is not taken for the same one", async () => {
    const { archive, manifest } = packed();
    await withEnv({ NUGET_KEY: "k" }, async () => {
        const { server } = await serve((request, response, base) => {
            if (request.url === "/v3/index.json") {
                response.writeHead(200, { "Content-Type": "application/json" });
                response.end(JSON.stringify({ resources: [
                    { "@id": `${base}/push`, "@type": "PackagePublish/2.0.0" },
                    { "@id": `${base}/flat/`, "@type": "PackageBaseAddress/3.0.0" }] }));
            } else if (request.method === "PUT") {
                response.writeHead(409); response.end();
            } else {
                response.writeHead(404); response.end();
            }
        });
        try {
            await assert.rejects(
                publish(archive, manifest, "nuget", { idPrefix: "Contoso", repository: `http://127.0.0.1:${server.address().port}/v3/index.json`, tokenEnv: "NUGET_KEY" }),
                (e) => e instanceof ChannelError && /cannot be read back/.test(e.message));
        } finally {
            server.close();
        }
    });
});

// ------------------------------------------------------------------ npm and gh, as stand-ins on PATH

/** A directory holding a stand-in `name` executable that runs `script` (node), logging its arguments. */
function standIn(name, script) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), `aop-${name}-`));
    const file = path.join(dir, name);
    fs.writeFileSync(file, `#!/usr/bin/env node\nconst args = process.argv.slice(2);\n` +
        `require("fs").appendFileSync(process.env.STANDIN_LOG, ${JSON.stringify(name)} + " " + args.join(" ") + "\\n");\n${script}\n`);
    fs.chmodSync(file, 0o755);
    return { dir, log: path.join(dir, "calls.log") };
}

const NPM = `
const env = process.env;
if (args[0] === "pack") { console.log(JSON.stringify([{ integrity: env.LOCAL_INTEGRITY }])); process.exit(0); }
if (args[0] === "view") {
    if (env.PUBLISHED === "E404") { console.error("npm error code E404"); process.exit(1); }
    if (env.PUBLISHED === "BROKEN") { console.error("npm error code ECONNREFUSED"); process.exit(1); }
    console.log(env.PUBLISHED || ""); process.exit(0);
}
process.exit(0);`;

test("npm: a version the registry already has is left alone when identical, and refused when not", async () => {
    const { archive, manifest } = packed();
    const npm = standIn("npm", NPM);
    for (const [published, expected, publishes] of [
        ["E404", "published", true],
        ["", "published", true],
        ["sha512-same", "same", false],
        ["sha512-other", /already has orders@1\.0\.0, and it is not this package/, false],
        ["BROKEN", /could not ask npm whether orders@1\.0\.0 is published: npm error code ECONNREFUSED/, false],
    ]) {
        fs.writeFileSync(npm.log, "");
        await withEnv({ PATH: `${npm.dir}${path.delimiter}${process.env.PATH}`, STANDIN_LOG: npm.log,
            LOCAL_INTEGRITY: "sha512-same", PUBLISHED: published }, async () => {
            const call = () => publish(archive, manifest, "npm", { registry: "http://registry.invalid" });
            if (expected instanceof RegExp) {
                assert.throws(call, (e) => e instanceof ChannelError && expected.test(e.message), published);
            } else {
                assert.strictEqual(call().alreadyPublished === true, expected === "same", published);
            }
        });
        const calls = fs.readFileSync(npm.log, "utf8");
        assert.strictEqual(/^npm publish --tag latest --registry http:\/\/registry\.invalid$/m.test(calls), publishes, calls);
    }
});

const GH = `
const env = process.env;
if (args[0] === "release" && args[1] === "view" && !args.includes("--json")) process.exit(env.RELEASE === "yes" ? 0 : 1);
if (args[0] === "release" && args[1] === "view") { console.log(env.ASSETS || ""); process.exit(0); }
if (args[0] === "release" && args[1] === "download") {
    const dir = args[args.indexOf("--dir") + 1], name = args[args.indexOf("--pattern") + 1];
    require("fs").copyFileSync(env.HELD, require("path").join(dir, name));
}
process.exit(0);`;

test("github-release: an asset the release already has is left alone when identical, and refused when not; never clobbered", async () => {
    const { archive, manifest } = packed();
    const other = path.join(os.tmpdir(), `aop-other-${process.pid}.tgz`);
    fs.writeFileSync(other, "different");
    const gh = standIn("gh", GH);
    const asset = path.basename(archive);
    for (const [release, assets, held, expected, uploads, creates] of [
        ["no", "", archive, "uploaded", true, true],
        ["yes", "other.tgz", archive, "uploaded", true, false],
        ["yes", `x.tgz\n${asset}`, archive, "same", false, false],
        ["yes", asset, other, /already has orders-1\.0\.0\.tgz, and it is not this archive/, false, false],
    ]) {
        fs.writeFileSync(gh.log, "");
        await withEnv({ PATH: `${gh.dir}${path.delimiter}${process.env.PATH}`, STANDIN_LOG: gh.log,
            RELEASE: release, ASSETS: assets, HELD: held }, async () => {
            const call = () => publish(archive, manifest, "github-release", { repository: "o/r" });
            if (expected instanceof RegExp) {
                assert.throws(call, (e) => e instanceof ChannelError && expected.test(e.message));
            } else {
                assert.strictEqual(call().alreadyPublished === true, expected === "same");
            }
        });
        const calls = fs.readFileSync(gh.log, "utf8");
        assert.strictEqual(/^gh release upload /m.test(calls), uploads, calls);
        assert.strictEqual(/^gh release create /m.test(calls), creates, calls);
        assert.doesNotMatch(calls, /--clobber/);
    }
});
