"use strict";

// Publishing a version the destination already has: success when it holds the same
// documents, a failure naming the version when it does not. The documents decide, not the
// bytes: the same documents packed from a later commit are another archive, and the same
// contract. Every remote channel, with no registry and no network -- HTTP stubs on
// localhost, and stand-in npm and gh executables on PATH.

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

// `epoch` stands in for another commit: the manifest's producedAt, and so the archive's
// bytes, change while the documents do not. `content` changes the documents themselves.
function packed(target = "orders", version = "1.0.0", { epoch = "1767225600", content = "" } = {}) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-idem-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"),
        `schemaVersion: 1\nsources:\n  root: specs\n  openapi: openapi\ndefaults:\n  openapi:\n    outputName: openapi.yaml\n` +
        `targets:\n  ${target}:\n    openapi:\n      bundle: bundles/${target}.yaml\n`);
    const config = load(path.join(dir, "apionly.yaml"));
    fs.mkdirSync(config.distDir(target), { recursive: true });
    fs.writeFileSync(path.join(config.distDir(target), "openapi.yaml"), `openapi: 3.1.1\ninfo:\n  version: ${version}\n${content}`);
    process.env.SOURCE_DATE_EPOCH = epoch;
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
            requests.push({ method: request.method, url: request.url, headers: request.headers, body: Buffer.concat(chunks) });
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

/** The three files a version consists of in a Maven repository, exactly as the channel writes them. */
function mavenFiles(pack) {
    const local = publish(pack.archive, pack.manifest, "maven", { groupId: "com.example", repository: "m2", baseDir: pack.config.root });
    const dir = path.dirname(local.location);
    return Object.fromEntries(["orders-1.0.0.tgz", "orders-1.0.0.pom", "orders-1.0.0-manifest.json"]
        .map((name) => [name, fs.readFileSync(path.join(dir, name))]));
}

test("maven: a version the repository already has is left alone with the same documents, completed when partial, and refused when not", async () => {
    const { archive, manifest, ...mine } = packed();
    const files = mavenFiles({ archive, manifest, ...mine });
    const later = mavenFiles(packed("orders", "1.0.0", { epoch: "1767312000" }));
    const changed = mavenFiles(packed("orders", "1.0.0", { content: "x-changed: true\n" }));
    assert.ok(!later["orders-1.0.0.tgz"].equals(files["orders-1.0.0.tgz"]), "another commit packs to other bytes");
    const other = Buffer.from("something else");
    await withEnv({ M2_KEY: "t" }, async () => {
        for (const [label, held, expected, puts] of [
            ["nothing", {}, "published", Object.keys(files)],
            ["everything, identical", files, "same", []],
            ["everything, from another commit", later, "same", []],
            // A run interrupted after the artifact: the next one completes the version.
            ["only the artifact", { "orders-1.0.0.tgz": files["orders-1.0.0.tgz"] }, "published", ["orders-1.0.0.pom", "orders-1.0.0-manifest.json"]],
            ["only another commit's artifact", { "orders-1.0.0.tgz": later["orders-1.0.0.tgz"] }, "published", ["orders-1.0.0.pom", "orders-1.0.0-manifest.json"]],
            // A POM carries no documents, and is left as it is.
            ["another POM", { ...files, "orders-1.0.0.pom": other }, "same", []],
            ["other documents", changed, /already has com\.example:orders:1\.0\.0, and its orders-1\.0\.0\.tgz is not this one/, []],
            ["another manifest", { "orders-1.0.0.tgz": files["orders-1.0.0.tgz"], "orders-1.0.0-manifest.json": changed["orders-1.0.0-manifest.json"] },
                /its orders-1\.0\.0-manifest\.json is not this one/, []],
            ["an artifact that cannot be read", { ...files, "orders-1.0.0.tgz": other },
                /already has com\.example:orders:1\.0\.0, and its orders-1\.0\.0\.tgz cannot be compared: .*not a gzipped tar archive/, []],
        ]) {
            const { server, requests, base } = await serve((request, response) => {
                const name = request.url.split("/").pop();
                if (request.method === "GET") {
                    if (held[name]) { response.writeHead(200); response.end(held[name]); } else { response.writeHead(404); response.end(); }
                    return;
                }
                response.writeHead(201); response.end();
            });
            try {
                const call = publish(archive, manifest, "maven", { groupId: "com.example", repository: `${base}/repo`, tokenEnv: "M2_KEY" });
                if (expected instanceof RegExp) {
                    await assert.rejects(call, (e) => e instanceof ChannelError && expected.test(e.message)
                        && /A published version is never replaced/.test(e.message), label);
                } else {
                    const result = await call;
                    assert.strictEqual(result.alreadyPublished === true, expected === "same", label);
                }
                // The version's own files; maven-metadata.xml has tests of its own (maven-remote.test.js).
                const sent = requests.filter((r) => r.method === "PUT" && !r.url.endsWith("/maven-metadata.xml"));
                assert.deepStrictEqual(sent.map((r) => r.url.split("/").pop()), puts, label);
                // A completed version keeps the provenance of the artifact it already has.
                const manifestPut = sent.find((r) => r.url.endsWith("-manifest.json"));
                if (manifestPut && held["orders-1.0.0.tgz"]) {
                    const source = held["orders-1.0.0.tgz"] === later["orders-1.0.0.tgz"] ? later : files;
                    assert.ok(manifestPut.body.equals(source["orders-1.0.0-manifest.json"]), label);
                }
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

/** The .nupkg the nuget channel builds from `pack`, as a feed would hold it. */
function nupkgOf(pack) {
    return fs.readFileSync(publish(pack.archive, pack.manifest, "nuget", { idPrefix: "Contoso", repository: "feed", baseDir: pack.config.root }).location);
}

test("nuget: a version the feed already has is success when identical, and refused when not or unreadable", async () => {
    const mine = packed();
    const { archive, manifest } = mine;
    const bytes = nupkgOf(mine);
    const later = nupkgOf(packed("orders", "1.0.0", { epoch: "1767312000" }));
    const changed = nupkgOf(packed("orders", "1.0.0", { content: "x-changed: true\n" }));
    await withEnv({ NUGET_KEY: "k" }, async () => {
        for (const [held, contentResource, expected] of [
            [bytes, true, "same"],
            [later, true, "same"],
            [changed, true, /already has Contoso\.Orders 1\.0\.0, and it is not this package/],
            [Buffer.from("other"), true, /already has Contoso\.Orders 1\.0\.0, and it cannot be compared: .*not a ZIP archive/],
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

test("nuget: a version the feed already has is read back before the push, on a feed that would accept a duplicate", async () => {
    // GitLab allows duplicate versions by default: a push of a different package under
    // an existing version answers 201, and the feed serves the new one from then on.
    const mine = packed();
    const { archive, manifest } = mine;
    const bytes = nupkgOf(mine);
    const later = nupkgOf(packed("orders", "1.0.0", { epoch: "1767312000" }));
    const changed = nupkgOf(packed("orders", "1.0.0", { content: "x-changed: true\n" }));
    await withEnv({ NUGET_KEY: "k" }, async () => {
        for (const [held, expected, pushes] of [
            [null, "published", true],
            [bytes, "same", false],
            [later, "same", false],
            [changed, /already has Contoso\.Orders 1\.0\.0, and it is not this package/, false],
            [500, /could not read Contoso\.Orders 1\.0\.0 back from .*\/flat\/.*: 500/, false],
        ]) {
            const { server, requests } = await serve((request, response, base) => {
                if (request.url === "/v3/index.json") {
                    response.writeHead(200, { "Content-Type": "application/json" });
                    response.end(JSON.stringify({ resources: [
                        { "@id": `${base}/push`, "@type": "PackagePublish/2.0.0" },
                        { "@id": `${base}/flat`, "@type": "PackageBaseAddress/3.0.0" }] }));
                } else if (request.method === "PUT") {
                    response.writeHead(201); response.end();
                } else if (typeof held === "number") {
                    response.writeHead(held); response.end();
                } else if (held && request.url === "/flat/contoso.orders/1.0.0/contoso.orders.1.0.0.nupkg") {
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
                    assert.strictEqual((await call).alreadyPublished === true, expected === "same", String(held));
                }
                assert.strictEqual(requests.some((r) => r.method === "PUT"), pushes, String(held));
                const readBack = requests.find((r) => r.url.startsWith("/flat/"));
                assert.ok(readBack, "the feed's copy is asked for");
                assert.strictEqual(readBack.headers["x-nuget-apikey"], "k");
            } finally {
                server.close();
            }
        }
    });
});

test("nuget: a version that appears between the read-back and the push is compared after the 409", async () => {
    // Another publisher pushing at the same moment, or a feed that serves a fresh push
    // only after a while, as GitLab's does: absent when read, there when pushed.
    const mine = packed();
    const { archive, manifest } = mine;
    const bytes = nupkgOf(mine);
    const later = nupkgOf(packed("orders", "1.0.0", { epoch: "1767312000" }));
    const changed = nupkgOf(packed("orders", "1.0.0", { content: "x-changed: true\n" }));
    await withEnv({ NUGET_KEY: "k" }, async () => {
        for (const [held, expected] of [
            [bytes, "same"],
            [later, "same"],
            [changed, /already has Contoso\.Orders 1\.0\.0, and it is not this package/],
        ]) {
            let pushed = false;
            const { server } = await serve((request, response, base) => {
                if (request.url === "/v3/index.json") {
                    response.writeHead(200, { "Content-Type": "application/json" });
                    response.end(JSON.stringify({ resources: [
                        { "@id": `${base}/push`, "@type": "PackagePublish/2.0.0" },
                        { "@id": `${base}/flat`, "@type": "PackageBaseAddress/3.0.0" }] }));
                } else if (request.method === "PUT") {
                    pushed = true;
                    response.writeHead(409); response.end();
                } else if (pushed) {
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
                }
                assert.ok(pushed);
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

// `npm view <name>@<version> dist --json` prints the version's dist object, or nothing
// when the package exists without that version; PUBLISHED is what the registry holds.
// `npm pack <name>@<version>` downloads the package the registry holds, HELD_PACKAGE.
const NPM = `
const env = process.env;
if (args[0] === "pack" && args[1] && args[1].includes("@")) {
    if (env.HELD_PACKAGE === "BROKEN") { console.error("npm error code E401"); process.exit(1); }
    const dir = args[args.indexOf("--pack-destination") + 1];
    require("fs").copyFileSync(env.HELD_PACKAGE, require("path").join(dir, "orders-1.0.0.tgz"));
    console.log(JSON.stringify([{ filename: "orders-1.0.0.tgz" }])); process.exit(0);
}
if (args[0] === "pack") { console.log(JSON.stringify([{ integrity: env.LOCAL_INTEGRITY, shasum: env.LOCAL_SHASUM }])); process.exit(0); }
if (args[0] === "view") {
    if (env.PUBLISHED === "E404") { console.error("npm error code E404"); process.exit(1); }
    if (env.PUBLISHED === "BROKEN") { console.error("npm error code ECONNREFUSED"); process.exit(1); }
    console.log(env.PUBLISHED || ""); process.exit(0);
}
process.exit(0);`;

/** The package the npm channel builds from `pack`, as a registry would hold it. */
function npmPackageOf(pack) {
    const out = fs.mkdtempSync(path.join(os.tmpdir(), "aop-npm-held-"));
    return publish(pack.archive, pack.manifest, "npm", { publish: false, directory: out }).location;
}

test("npm: a version the registry already has is left alone with the same documents, and refused when not", async () => {
    const { archive, manifest } = packed();
    const later = npmPackageOf(packed("orders", "1.0.0", { epoch: "1767312000" }));
    const changed = npmPackageOf(packed("orders", "1.0.0", { content: "x-changed: true\n" }));
    const npm = standIn("npm", NPM);
    const dist = (fields) => JSON.stringify({ tarball: "http://registry.invalid/orders-1.0.0.tgz", ...fields });
    for (const [published, held, expected, publishes, downloads] of [
        ["E404", null, "published", true, false],
        ["", null, "published", true, false],
        // The same package: its integrity says so, and nothing is downloaded.
        [dist({ integrity: "sha512-same", shasum: "sha1-same" }), null, "same", false, false],
        // A registry whose metadata has no integrity, such as GitLab's: the SHA-1 decides.
        [dist({ shasum: "sha1-same" }), null, "same", false, false],
        // Another package: the registry's copy is downloaded, and its documents decide.
        [dist({ integrity: "sha512-other", shasum: "sha1-same" }), later, "same", false, true],
        [dist({ integrity: "sha512-other", shasum: "sha1-same" }), changed, /already has orders@1\.0\.0, and it is not this package/, false, true],
        [dist({ shasum: "sha1-other" }), later, "same", false, true],
        [dist({ shasum: "sha1-other" }), changed, /already has orders@1\.0\.0, and it is not this package/, false, true],
        // A registry that records neither: only the documents can tell.
        [dist({}), later, "same", false, true],
        [dist({}), changed, /already has orders@1\.0\.0, and it is not this package/, false, true],
        [dist({ integrity: "sha512-other" }), "BROKEN", /could not read orders@1\.0\.0 back from npm to compare: npm error code E401/, false, true],
        ["BROKEN", null, /could not ask npm whether orders@1\.0\.0 is published: npm error code ECONNREFUSED/, false, false],
    ]) {
        const label = `${published} / ${held === later ? "later" : held === changed ? "changed" : held}`;
        fs.writeFileSync(npm.log, "");
        await withEnv({ PATH: `${npm.dir}${path.delimiter}${process.env.PATH}`, STANDIN_LOG: npm.log,
            LOCAL_INTEGRITY: "sha512-same", LOCAL_SHASUM: "sha1-same", PUBLISHED: published, HELD_PACKAGE: held || "" }, async () => {
            const call = () => publish(archive, manifest, "npm", { registry: "http://registry.invalid" });
            if (expected instanceof RegExp) {
                assert.throws(call, (e) => e instanceof ChannelError && expected.test(e.message), label);
            } else {
                assert.strictEqual(call().alreadyPublished === true, expected === "same", label);
            }
        });
        const calls = fs.readFileSync(npm.log, "utf8");
        assert.strictEqual(/^npm publish --tag latest --registry http:\/\/registry\.invalid$/m.test(calls), publishes, `${label}\n${calls}`);
        assert.strictEqual(/^npm pack orders@1\.0\.0 --pack-destination \S+ --json --registry http:\/\/registry\.invalid$/m.test(calls),
            downloads, `${label}\n${calls}`);
    }
});

test("npm: the same archive packs to the same package whatever the umask of the process that publishes it", () => {
    // A re-run on a runner with another umask must be recognised as identical; the files
    // npm packs would otherwise carry the extracting process's modes.
    const { archive, manifest } = packed();
    const out = fs.mkdtempSync(path.join(os.tmpdir(), "aop-umask-"));
    const saved = process.umask();
    try {
        const tarballs = ["022", "077"].map((mask) => {
            process.umask(parseInt(mask, 8));
            const result = publish(archive, manifest, "npm", { publish: false, directory: path.join(out, mask) });
            return result.location;
        });
        assert.ok(fs.readFileSync(tarballs[0]).equals(fs.readFileSync(tarballs[1])), "the same bytes under umask 022 and 077");
        const listing = require("node:child_process").execFileSync("tar", ["-tvzf", tarballs[1]], { encoding: "utf8" });
        for (const line of listing.trim().split("\n")) assert.match(line, /^-rw-r--r--/, line);
    } finally {
        process.umask(saved);
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

test("github-release: an asset the release already has is left alone with the same documents, and refused when not; never clobbered", async () => {
    const { archive, manifest } = packed();
    const later = packed("orders", "1.0.0", { epoch: "1767312000" }).archive;
    const changed = packed("orders", "1.0.0", { content: "x-changed: true\n" }).archive;
    const other = path.join(os.tmpdir(), `aop-other-${process.pid}.tgz`);
    fs.writeFileSync(other, "different");
    const gh = standIn("gh", GH);
    const asset = path.basename(archive);
    for (const [release, assets, held, expected, uploads, creates] of [
        ["no", "", archive, "uploaded", true, true],
        ["yes", "other.tgz", archive, "uploaded", true, false],
        ["yes", `x.tgz\n${asset}`, archive, "same", false, false],
        ["yes", asset, later, "same", false, false],
        ["yes", asset, changed, /already has orders-1\.0\.0\.tgz, and it is not this archive/, false, false],
        ["yes", asset, other, /already has orders-1\.0\.0\.tgz, and it cannot be compared: .*not a gzipped tar archive/, false, false],
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
