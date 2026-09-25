"use strict";

const test = require("node:test");
const assert = require("node:assert");
const crypto = require("node:crypto");
const fs = require("node:fs");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { load } = require("../src/config");
const { pack } = require("../src/pack");
const { publish, ChannelError } = require("../src/channels");
const { packageId, nuspec } = require("../src/nuget");
const { readZip } = require("./zip-reader");

/** A library with one built target, packed at `version`: {config, archive, manifest}. */
function packed(target = "user-account", version = "1.2.0") {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-nuget-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"), `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
defaults:
  openapi:
    outputName: openapi.yaml
targets:
  ${target}:
    openapi:
      bundle: bundles/${target}.yaml
`);
    const config = load(path.join(dir, "apionly.yaml"));
    const dist = config.distDir(target);
    fs.mkdirSync(dist, { recursive: true });
    fs.writeFileSync(path.join(dist, "openapi.yaml"), `openapi: 3.1.1\ninfo:\n  title: T\n  version: ${version}\n`);
    fs.writeFileSync(path.join(dist, "asyncapi.yaml"), `asyncapi: 3.0.0\ninfo:\n  title: T\n  version: ${version}\n`);
    const result = pack(config, target, { version, closureSha256: "c", outDir: path.join(dir, "build/packages") });
    return { config, ...result };
}

function feed(config) {
    return path.join(config.root, "nuget-feed");
}

/** The files the packed archive holds, name to bytes. */
function archived(archive) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-nuget-x-"));
    execFileSync("tar", ["-xzf", archive, "-C", dir]);
    return Object.fromEntries(fs.readdirSync(dir).map((name) => [name, fs.readFileSync(path.join(dir, name))]));
}

// ------------------------------------------------------------------ identity

test("the package id is the prefix followed by the target in PascalCase, unless one is given", () => {
    assert.strictEqual(packageId("user-account", { idPrefix: "Contoso.Contracts" }), "Contoso.Contracts.UserAccount");
    assert.strictEqual(packageId("orders_v2.api", { idPrefix: "Contoso" }), "Contoso.OrdersV2Api");
    assert.strictEqual(packageId("user-account", { idPrefix: "Contoso", packageId: "Contoso.Users" }), "Contoso.Users");
});

test("a package id NuGet would refuse is refused, naming it, and so is a missing prefix", () => {
    for (const options of [{ idPrefix: "Contoso Contracts" }, { idPrefix: "Contoso..Contracts" },
        { idPrefix: "Contoso", packageId: "-Users" }, { idPrefix: "C".repeat(95) }]) {
        assert.throws(() => packageId("user-account", options),
            (e) => e instanceof ChannelError && /not a valid NuGet package id/.test(e.message), JSON.stringify(options));
    }
    assert.throws(() => packageId("user-account", {}),
        (e) => e instanceof ChannelError && /requires an idPrefix/.test(e.message));
});

// ------------------------------------------------------------------ the local feed

test("a local feed gets the package in the hierarchical layout nuget add writes, with provenance beside it", () => {
    const { config, archive, manifest } = packed();

    const result = publish(archive, manifest, "nuget", {
        idPrefix: "Contoso.Contracts", repository: "nuget-feed", baseDir: config.root,
    });

    const dir = path.join(feed(config), "contoso.contracts.useraccount", "1.2.0");
    const nupkg = path.join(dir, "contoso.contracts.useraccount.1.2.0.nupkg");
    assert.strictEqual(result.location, nupkg);
    assert.strictEqual(result.id, "Contoso.Contracts.UserAccount");
    assert.deepStrictEqual(fs.readdirSync(dir).sort(), [
        "contoso.contracts.useraccount.1.2.0.nupkg",
        "contoso.contracts.useraccount.1.2.0.nupkg.sha512",
        "contoso.contracts.useraccount.nuspec",
        "manifest.json",
    ]);
    assert.strictEqual(fs.readFileSync(`${nupkg}.sha512`, "utf8"),
        crypto.createHash("sha512").update(fs.readFileSync(nupkg)).digest("base64"));
    assert.deepStrictEqual(JSON.parse(fs.readFileSync(path.join(dir, "manifest.json"), "utf8")), manifest);
});

test("the package holds the nuspec, the packaging parts, and exactly what pack produced under contracts/", () => {
    const { config, archive, manifest } = packed();
    const { location } = publish(archive, manifest, "nuget", {
        idPrefix: "Contoso.Contracts", repository: "nuget-feed", baseDir: config.root,
    });

    const entries = readZip(fs.readFileSync(location));

    assert.deepStrictEqual(entries.map((e) => e.name), [
        "Contoso.Contracts.UserAccount.nuspec",
        "[Content_Types].xml",
        "_rels/.rels",
        "contracts/asyncapi.yaml",
        "contracts/manifest.json",
        "contracts/openapi.yaml",
    ]);
    for (const [name, bytes] of Object.entries(archived(archive))) {
        assert.ok(entries.find((e) => e.name === `contracts/${name}`).data.equals(bytes), name);
    }
    const rels = entries.find((e) => e.name === "_rels/.rels").data.toString();
    assert.match(rels, /Target="\/Contoso\.Contracts\.UserAccount\.nuspec"/);
    const types = entries.find((e) => e.name === "[Content_Types].xml").data.toString();
    for (const extension of ["rels", "nuspec", "yaml", "json"]) {
        assert.match(types, new RegExp(`Extension="${extension}"`), extension);
    }
});

test("publishing the same packed archive twice produces byte-identical packages", () => {
    const { config, archive, manifest } = packed();
    const once = publish(archive, manifest, "nuget", { idPrefix: "Contoso", repository: "a", baseDir: config.root });
    const twice = publish(archive, manifest, "nuget", { idPrefix: "Contoso", repository: "b", baseDir: config.root });

    assert.ok(fs.readFileSync(once.location).equals(fs.readFileSync(twice.location)));
});

test("the nuspec carries id, version, authors, description, licence and the source repository", () => {
    const manifest = {
        target: "user-account", version: "1.2.0",
        source: { repository: "https://github.com/example/specs.git", commit: "abc123" },
    };

    const withDefaults = nuspec("Contoso.Contracts.UserAccount", manifest, { idPrefix: "Contoso.Contracts" });
    assert.match(withDefaults, /<id>Contoso\.Contracts\.UserAccount<\/id>/);
    assert.match(withDefaults, /<version>1\.2\.0<\/version>/);
    assert.match(withDefaults, /<authors>Contoso\.Contracts<\/authors>/);
    assert.match(withDefaults, /<description>API description documents for user-account\.<\/description>/);
    assert.match(withDefaults, /<license type="expression">UNLICENSED<\/license>/);
    assert.match(withDefaults, /<repository type="git" url="https:\/\/github\.com\/example\/specs\.git" commit="abc123" \/>/);

    const configured = nuspec("X", { target: "a & b", version: "1.0.0", source: {} },
        { idPrefix: "X", authors: "Contoso <APIs>", license: "MIT" });
    assert.match(configured, /<authors>Contoso &lt;APIs&gt;<\/authors>/);
    assert.match(configured, /for a &amp; b\./);
    assert.match(configured, /<license type="expression">MIT<\/license>/);
    assert.doesNotMatch(configured, /<repository/);
});

test("a pre-release version publishes as a NuGet pre-release", () => {
    const { config, archive, manifest } = packed("orders", "2.1.0-rc.1");

    const result = publish(archive, manifest, "nuget", { idPrefix: "Contoso", repository: "nuget-feed", baseDir: config.root });

    assert.strictEqual(result.prerelease, true);
    assert.strictEqual(result.location,
        path.join(feed(config), "contoso.orders", "2.1.0-rc.1", "contoso.orders.2.1.0-rc.1.nupkg"));
    const nuspecEntry = readZip(fs.readFileSync(result.location)).find((e) => e.name.endsWith(".nuspec"));
    assert.match(nuspecEntry.data.toString(), /<version>2\.1\.0-rc\.1<\/version>/);
});

test("a version with build metadata is refused before anything is written", () => {
    const { config, archive, manifest } = packed("orders", "2.1.0+build.7");

    assert.throws(
        () => publish(archive, manifest, "nuget", { idPrefix: "Contoso", repository: "nuget-feed", baseDir: config.root }),
        (e) => e instanceof ChannelError && /2\.1\.0\+build\.7/.test(e.message) && /build metadata/.test(e.message));
    assert.strictEqual(fs.existsSync(feed(config)), false);
});

test("an invalid package id is refused before anything is written", () => {
    const { config, archive, manifest } = packed();

    assert.throws(
        () => publish(archive, manifest, "nuget", { idPrefix: "Not Valid", repository: "nuget-feed", baseDir: config.root }),
        (e) => e instanceof ChannelError && /Not Valid/.test(e.message));
    assert.strictEqual(fs.existsSync(feed(config)), false);
});

test("a nuget channel with no repository is refused, saying what it needs", () => {
    const { archive, manifest } = packed();
    assert.throws(() => publish(archive, manifest, "nuget", { idPrefix: "Contoso" }),
        (e) => e instanceof ChannelError && /requires a repository/.test(e.message));
});

// ------------------------------------------------------------------ a remote feed

test("a remote feed with no API key in the environment fails, naming the variable", () => {
    delete process.env.SOME_NUGET_KEY;
    assert.throws(
        () => publish("/tmp/x.tgz", { target: "a", version: "1.0.0" }, "nuget", {
            idPrefix: "Contoso", repository: "https://nuget.example.invalid/v3/index.json", tokenEnv: "SOME_NUGET_KEY",
        }),
        (e) => e instanceof ChannelError && /no API key in \$SOME_NUGET_KEY/.test(e.message)
            && /channels\.nuget\.tokenEnv/.test(e.message));
});

/** A NuGet feed on localhost: a service index, and a publish endpoint that records what it was sent. */
async function stubFeed({ status = 201, publishResource = true } = {}) {
    const received = [];
    const server = http.createServer((request, response) => {
        const chunks = [];
        request.on("data", (chunk) => chunks.push(chunk));
        request.on("end", () => {
            const body = Buffer.concat(chunks);
            const base = `http://127.0.0.1:${server.address().port}`;
            if (request.method === "GET" && request.url === "/v3/index.json") {
                const resources = [{ "@id": `${base}/query`, "@type": "SearchQueryService" }];
                if (publishResource) resources.push({ "@id": `${base}/api/v2/package`, "@type": "PackagePublish/2.0.0" });
                response.writeHead(200, { "Content-Type": "application/json" });
                response.end(JSON.stringify({ version: "3.0.0", resources }));
                return;
            }
            received.push({ method: request.method, url: request.url, headers: request.headers, body });
            response.writeHead(status);
            response.end();
        });
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    return { server, received, index: `http://127.0.0.1:${server.address().port}/v3/index.json` };
}

test("a remote feed receives the package as a multipart PUT to the publish endpoint its service index names", async () => {
    const { config, archive, manifest } = packed();
    const { server, received, index } = await stubFeed({ status: 202 });
    process.env.TEST_NUGET_KEY = "k3y";
    try {
        const result = await publish(archive, manifest, "nuget", {
            idPrefix: "Contoso.Contracts", repository: index, tokenEnv: "TEST_NUGET_KEY", baseDir: config.root,
        });

        assert.strictEqual(received.length, 1);
        const [request] = received;
        assert.strictEqual(request.method, "PUT");
        assert.strictEqual(request.url, "/api/v2/package");
        assert.strictEqual(request.headers["x-nuget-apikey"], "k3y");
        const boundary = /boundary=(\S+)/.exec(request.headers["content-type"])[1];
        assert.match(request.headers["content-type"], /^multipart\/form-data;/);
        const local = publish(archive, manifest, "nuget", { idPrefix: "Contoso.Contracts", repository: "f", baseDir: config.root });
        const nupkg = fs.readFileSync(local.location);
        const start = request.body.indexOf("\r\n\r\n") + 4;
        assert.ok(request.body.subarray(start, start + nupkg.length).equals(nupkg), "the first part is the .nupkg, unchanged");
        assert.ok(request.body.subarray(start + nupkg.length).toString().startsWith(`\r\n--${boundary}--`));
        assert.strictEqual(result.id, "Contoso.Contracts.UserAccount");
        assert.match(result.location, /\/api\/v2\/package$/);
    } finally {
        delete process.env.TEST_NUGET_KEY;
        server.close();
    }
});

test("a remote feed that already has the version, rejects the package, or offers no publish endpoint is reported", async () => {
    const { config, archive, manifest } = packed();
    process.env.TEST_NUGET_KEY = "k3y";
    try {
        for (const [options, expected] of [
            [{ status: 409 }, /already has Contoso\.UserAccount 1\.2\.0/],
            [{ status: 400 }, /refused the package: 400/],
            [{ publishResource: false }, /no PackagePublish\/2\.0\.0 resource/],
        ]) {
            const { server, index } = await stubFeed(options);
            try {
                await assert.rejects(
                    publish(archive, manifest, "nuget", { idPrefix: "Contoso", repository: index, tokenEnv: "TEST_NUGET_KEY" }),
                    (e) => e instanceof ChannelError && expected.test(e.message), JSON.stringify(options));
            } finally {
                server.close();
            }
        }
    } finally {
        delete process.env.TEST_NUGET_KEY;
    }
});

test("a service index that cannot be read is reported with its URL", async () => {
    const server = http.createServer((request, response) => { response.writeHead(404); response.end(); });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const index = `http://127.0.0.1:${server.address().port}/v3/index.json`;
    process.env.TEST_NUGET_KEY = "k3y";
    try {
        const { archive, manifest } = packed();
        await assert.rejects(
            publish(archive, manifest, "nuget", { idPrefix: "Contoso", repository: index, tokenEnv: "TEST_NUGET_KEY" }),
            (e) => e instanceof ChannelError && e.message.includes(index) && /404/.test(e.message));
    } finally {
        delete process.env.TEST_NUGET_KEY;
        server.close();
    }
});
