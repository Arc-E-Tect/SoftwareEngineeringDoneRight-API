"use strict";

// The maven channel's remote mode: which header carries the token, and the
// maven-metadata.xml it keeps, so that update bots see a new version on a repository
// that does not generate the file itself, as GitLab's does not.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");

const { load } = require("../src/config");
const { pack } = require("../src/pack");
const { publish, ChannelError } = require("../src/channels");

function packed(version = "1.0.0") {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-maven-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"),
        "schemaVersion: 1\nsources:\n  root: specs\n  openapi: openapi\ndefaults:\n  openapi:\n    outputName: openapi.yaml\n" +
        "targets:\n  orders:\n    openapi:\n      bundle: bundles/orders.yaml\n");
    const config = load(path.join(dir, "apionly.yaml"));
    fs.mkdirSync(config.distDir("orders"), { recursive: true });
    fs.writeFileSync(path.join(config.distDir("orders"), "openapi.yaml"), `openapi: 3.1.1\ninfo:\n  version: ${version}\n`);
    process.env.SOURCE_DATE_EPOCH = "1767225600"; // 2026-01-01T00:00:00Z
    try {
        return { config, ...pack(config, "orders", { version, closureSha256: "c", outDir: path.join(dir, "packages") }) };
    } finally {
        delete process.env.SOURCE_DATE_EPOCH;
    }
}

/** A Maven repository on localhost: `files` maps a path below /repo to bytes. */
async function repository({ files = {}, metadataPut = 201, metadataGet = null } = {}) {
    const requests = [];
    const server = http.createServer((request, response) => {
        const chunks = [];
        request.on("data", (c) => chunks.push(c));
        request.on("end", () => {
            const body = Buffer.concat(chunks);
            const key = request.url.replace(/^\/repo\//, "");
            requests.push({ method: request.method, key, headers: request.headers, body });
            const metadata = key.endsWith("maven-metadata.xml");
            if (request.method === "GET") {
                if (metadata && metadataGet) { response.writeHead(metadataGet); return response.end(); }
                if (files[key]) { response.writeHead(200); return response.end(files[key]); }
                response.writeHead(404); return response.end();
            }
            if (metadata && metadataPut !== 201) { response.writeHead(metadataPut); return response.end("refused"); }
            files[key] = body;
            response.writeHead(201); response.end();
        });
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    return { server, requests, files, url: `http://127.0.0.1:${server.address().port}/repo` };
}

async function withToken(body) {
    process.env.M2_TOKEN = "t0k";
    try {
        return await body();
    } finally {
        delete process.env.M2_TOKEN;
    }
}

const METADATA = "com/example/orders/maven-metadata.xml";

function metadata(versions, { latest, release, lastUpdated = "20260101000000" }) {
    return '<?xml version="1.0" encoding="UTF-8"?>\n<metadata>\n  <groupId>com.example</groupId>\n  <artifactId>orders</artifactId>\n' +
        `  <versioning>\n    <latest>${latest}</latest>\n` + (release ? `    <release>${release}</release>\n` : "") +
        `    <versions>\n${versions.map((v) => `      <version>${v}</version>\n`).join("")}    </versions>\n` +
        `    <lastUpdated>${lastUpdated}</lastUpdated>\n  </versioning>\n</metadata>\n`;
}

// ------------------------------------------------------------------ tokenHeader

test("tokenHeader names the header that carries the token, raw, on every request", async () => {
    const { archive, manifest } = packed();
    const repo = await repository();
    try {
        await withToken(async () => {
            await publish(archive, manifest, "maven",
                { groupId: "com.example", repository: repo.url, tokenEnv: "M2_TOKEN", tokenHeader: "Job-Token" });
            assert.ok(repo.requests.length >= 6);
            for (const request of repo.requests) {
                assert.strictEqual(request.headers["job-token"], "t0k", `${request.method} ${request.key}`);
                assert.strictEqual(request.headers.authorization, undefined);
            }
        });
    } finally {
        repo.server.close();
    }
});

test("without tokenHeader the token is a bearer token, as it has been", async () => {
    const { archive, manifest } = packed();
    const repo = await repository();
    try {
        await withToken(async () => {
            await publish(archive, manifest, "maven", { groupId: "com.example", repository: repo.url, tokenEnv: "M2_TOKEN" });
            for (const request of repo.requests) assert.strictEqual(request.headers.authorization, "Bearer t0k");
        });
    } finally {
        repo.server.close();
    }
});

test("a tokenHeader that is not a header name is refused before anything is sent", async () => {
    const { archive, manifest } = packed();
    await withToken(async () => {
        for (const tokenHeader of ["Job Token", "", "Job-Token:", "Tok\nen"]) {
            assert.throws(() => publish(archive, manifest, "maven",
                { groupId: "com.example", repository: "http://127.0.0.1:9/repo", tokenEnv: "M2_TOKEN", tokenHeader }),
            (e) => e instanceof ChannelError && /channels\.maven\.tokenHeader must be an HTTP header name/.test(e.message),
            JSON.stringify(tokenHeader));
        }
    });
});

// ------------------------------------------------------------------ maven-metadata.xml

test("a first version writes maven-metadata.xml, its lastUpdated the commit's time", async () => {
    const { archive, manifest } = packed();
    const repo = await repository();
    try {
        await withToken(async () => {
            await publish(archive, manifest, "maven", { groupId: "com.example", repository: repo.url, tokenEnv: "M2_TOKEN" });
            assert.strictEqual(repo.files[METADATA].toString(), metadata(["1.0.0"], { latest: "1.0.0", release: "1.0.0" }));
            const metadataPut = repo.requests.find((r) => r.method === "PUT" && r.key === METADATA);
            assert.strictEqual(metadataPut.headers["content-type"], "application/xml");
        });
    } finally {
        repo.server.close();
    }
});

test("a new version joins the versions listed; latest is the highest, release the highest release", async () => {
    const { archive, manifest } = packed("1.0.0");
    const held = metadata(["0.9.0", "1.1.0-rc.1", "0.10.0"], { latest: "1.1.0-rc.1", release: "0.10.0", lastUpdated: "20250101000000" });
    const repo = await repository({ files: { [METADATA]: Buffer.from(held) } });
    try {
        await withToken(async () => {
            await publish(archive, manifest, "maven", { groupId: "com.example", repository: repo.url, tokenEnv: "M2_TOKEN" });
            assert.strictEqual(repo.files[METADATA].toString(),
                metadata(["0.9.0", "1.1.0-rc.1", "0.10.0", "1.0.0"], { latest: "1.1.0-rc.1", release: "1.0.0" }));
        });
    } finally {
        repo.server.close();
    }
});

test("a pre-release is listed and may be latest, but is never release; with only pre-releases there is no release", async () => {
    const { archive, manifest } = packed("2.0.0-rc.1");
    const repo = await repository();
    try {
        await withToken(async () => {
            await publish(archive, manifest, "maven", { groupId: "com.example", repository: repo.url, tokenEnv: "M2_TOKEN" });
            assert.strictEqual(repo.files[METADATA].toString(), metadata(["2.0.0-rc.1"], { latest: "2.0.0-rc.1" }));
        });
    } finally {
        repo.server.close();
    }
});

test("metadata that already lists the version, with the right latest and release, is not written again", async () => {
    const { archive, manifest } = packed();
    const repo = await repository();
    try {
        await withToken(async () => {
            const options = { groupId: "com.example", repository: repo.url, tokenEnv: "M2_TOKEN" };
            await publish(archive, manifest, "maven", options);
            repo.requests.length = 0;
            const again = await publish(archive, manifest, "maven", options);
            assert.strictEqual(again.alreadyPublished, true);
            assert.ok(!repo.requests.some((r) => r.method === "PUT"), "a second run writes nothing");
        });
    } finally {
        repo.server.close();
    }
});

test("an identical version still gets its metadata entry, as one published before the Publisher wrote metadata", async () => {
    const { archive, manifest } = packed();
    const repo = await repository();
    try {
        await withToken(async () => {
            const options = { groupId: "com.example", repository: repo.url, tokenEnv: "M2_TOKEN" };
            await publish(archive, manifest, "maven", options);
            delete repo.files[METADATA];
            repo.requests.length = 0;
            const again = await publish(archive, manifest, "maven", options);
            assert.strictEqual(again.alreadyPublished, true, "the version itself was there");
            assert.deepStrictEqual(repo.requests.filter((r) => r.method === "PUT").map((r) => r.key), [METADATA]);
        });
    } finally {
        repo.server.close();
    }
});

test("metadata: false leaves maven-metadata.xml alone, for a repository that generates its own", async () => {
    const { archive, manifest } = packed();
    const repo = await repository();
    try {
        await withToken(async () => {
            await publish(archive, manifest, "maven",
                { groupId: "com.example", repository: repo.url, tokenEnv: "M2_TOKEN", metadata: false });
            assert.ok(!repo.requests.some((r) => r.key === METADATA));
        });
    } finally {
        repo.server.close();
    }
});

test("metadata the repository refuses fails publish, naming metadata: false; a re-run retries only the metadata", async () => {
    const { archive, manifest } = packed();
    const repo = await repository({ metadataPut: 400 });
    try {
        await withToken(async () => {
            const options = { groupId: "com.example", repository: repo.url, tokenEnv: "M2_TOKEN" };
            await assert.rejects(publish(archive, manifest, "maven", options),
                (e) => e instanceof ChannelError && /maven-metadata\.xml.*400/.test(e.message)
                    && /channels\.maven\.metadata: false/.test(e.message));
            repo.requests.length = 0;
            await assert.rejects(publish(archive, manifest, "maven", options), /maven-metadata\.xml/);
            assert.deepStrictEqual(repo.requests.filter((r) => r.method === "PUT").map((r) => r.key), [METADATA]);
        });
    } finally {
        repo.server.close();
    }
});

test("metadata that cannot be read is reported rather than overwritten", async () => {
    const { archive, manifest } = packed();
    const repo = await repository({ metadataGet: 500 });
    try {
        await withToken(async () => {
            await assert.rejects(
                publish(archive, manifest, "maven", { groupId: "com.example", repository: repo.url, tokenEnv: "M2_TOKEN" }),
                (e) => e instanceof ChannelError && /GET .*maven-metadata\.xml failed: 500/.test(e.message));
            assert.ok(!repo.requests.some((r) => r.method === "PUT" && r.key === METADATA));
        });
    } finally {
        repo.server.close();
    }
});
