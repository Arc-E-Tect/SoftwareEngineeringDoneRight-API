"use strict";

// The gitlab-package channel, against a GitLab stand-in on localhost: the Generic
// Package Registry, and the Releases API. No network.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { load } = require("../src/config");
const { pack } = require("../src/pack");
const { publish, ChannelError } = require("../src/channels");
const { isRemote } = require("../src/remote");

const GITLAB_ENV = ["CI_API_V4_URL", "CI_PROJECT_ID", "CI_JOB_TOKEN", "CI_COMMIT_SHA", "GITLAB_TOKEN", "OTHER_TOKEN",
    "API_ONLY_PUBLISHER_REMOTE"];

function packed(target = "orders", version = "1.0.0") {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-gitlab-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"),
        `schemaVersion: 1\nsources:\n  root: specs\n  openapi: openapi\ndefaults:\n  openapi:\n    outputName: openapi.yaml\n` +
        `targets:\n  ${target}:\n    openapi:\n      bundle: bundles/${target}.yaml\n`);
    const config = load(path.join(dir, "apionly.yaml"));
    fs.mkdirSync(config.distDir(target), { recursive: true });
    fs.writeFileSync(path.join(config.distDir(target), "openapi.yaml"), `openapi: 3.1.1\ninfo:\n  version: ${version}\n`);
    process.env.SOURCE_DATE_EPOCH = "1767225600";
    try {
        const result = pack(config, target, { version, closureSha256: "c", outDir: path.join(dir, "packages") });
        return { dir, ...result, manifestBytes: Buffer.from(JSON.stringify(result.manifest, null, 2) + "\n") };
    } finally {
        delete process.env.SOURCE_DATE_EPOCH;
    }
}

/**
 * A GitLab on localhost. `files` maps a package file's path, below /api/v4, to the bytes
 * GitLab holds; a PUT stores into it unless `refuse` names the status to answer instead.
 * `releases` maps a tag to the release GitLab holds.
 */
async function gitlab({ files = {}, releases = {}, readStatus = null, refuse = null } = {}) {
    const requests = [];
    const server = http.createServer((request, response) => {
        const chunks = [];
        request.on("data", (c) => chunks.push(c));
        request.on("end", () => {
            const body = Buffer.concat(chunks);
            requests.push({ method: request.method, url: request.url, headers: request.headers, body });
            const url = request.url.replace(/^\/api\/v4/, "");
            const send = (status, payload) => {
                response.writeHead(status, { "Content-Type": "application/json" });
                response.end(payload === undefined ? "" : (Buffer.isBuffer(payload) ? payload : JSON.stringify(payload)));
            };
            const release = /^\/projects\/[^/]+\/releases(?:\/([^/]+))?(\/assets\/links)?$/.exec(url);
            if (release) {
                const tag = release[1] && decodeURIComponent(release[1]);
                if (request.method === "GET") return releases[tag] ? send(200, releases[tag]) : send(404, { message: "404 Not Found" });
                if (request.method === "POST" && release[2]) {
                    releases[tag].assets.links.push(JSON.parse(body));
                    return send(201, {});
                }
                if (request.method === "POST") {
                    const created = JSON.parse(body);
                    releases[created.tag_name] = { tag_name: created.tag_name, assets: { links: created.assets.links } };
                    return send(201, created);
                }
            }
            if (request.method === "GET") {
                if (readStatus) return send(readStatus, { message: "nope" });
                return files[url] ? send(200, files[url]) : send(404, { message: "404 Not Found" });
            }
            if (request.method === "PUT") {
                if (refuse && (!refuse.url || refuse.url === url)) {
                    if (refuse.store) files[url] = refuse.store;
                    return send(refuse.status, { message: refuse.message });
                }
                files[url] = body;
                return send(201, { message: "201 Created" });
            }
            send(405);
        });
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    return { server, requests, files, releases, api: `http://127.0.0.1:${server.address().port}/api/v4` };
}

async function withEnv(values, body) {
    const saved = Object.fromEntries(GITLAB_ENV.map((k) => [k, process.env[k]]));
    for (const k of GITLAB_ENV) delete process.env[k];
    Object.assign(process.env, values);
    try {
        return await body();
    } finally {
        for (const [k, v] of Object.entries(saved)) if (v === undefined) delete process.env[k]; else process.env[k] = v;
    }
}

const ARCHIVE = "/projects/42/packages/generic/orders/1.0.0/orders-1.0.0.tgz";
const MANIFEST = "/projects/42/packages/generic/orders/1.0.0/manifest.json";

// ------------------------------------------------------------------ settings and credentials

test("gitlab-package is a remote channel", () => {
    assert.strictEqual(isRemote("gitlab-package", {}), true);
});

test("in a GitLab CI job it needs no settings: the API, the project and the job token come from CI variables", async () => {
    const { archive, manifest, manifestBytes } = packed();
    const stub = await gitlab();
    try {
        await withEnv({ CI_API_V4_URL: stub.api, CI_PROJECT_ID: "42", CI_JOB_TOKEN: "job-t" }, async () => {
            const result = await publish(archive, manifest, "gitlab-package", {});
            assert.deepStrictEqual(stub.requests.map((r) => `${r.method} ${r.url}`), [
                `GET /api/v4${ARCHIVE}`, `PUT /api/v4${ARCHIVE}`, `GET /api/v4${MANIFEST}`, `PUT /api/v4${MANIFEST}`,
            ]);
            for (const request of stub.requests) {
                assert.strictEqual(request.headers["job-token"], "job-t");
                assert.strictEqual(request.headers["private-token"], undefined);
            }
            assert.ok(stub.files[ARCHIVE].equals(fs.readFileSync(archive)));
            assert.ok(stub.files[MANIFEST].equals(manifestBytes));
            assert.strictEqual(result.alreadyPublished, undefined);
            assert.strictEqual(result.location, `${stub.api}/projects/42/packages/generic/orders/1.0.0/orders-1.0.0.tgz`);
        });
    } finally {
        stub.server.close();
    }
});

test("configuration wins over CI variables, and a project path is URL-encoded", async () => {
    const { archive, manifest } = packed();
    const stub = await gitlab();
    try {
        await withEnv({ CI_API_V4_URL: "http://127.0.0.1:9/api/v4", CI_PROJECT_ID: "7", CI_JOB_TOKEN: "job-t", GITLAB_TOKEN: "pat" }, async () => {
            await publish(archive, manifest, "gitlab-package", { url: `${stub.api}/`, project: "group/sub group/lib" });
            assert.strictEqual(stub.requests[0].url,
                "/api/v4/projects/group%2Fsub%20group%2Flib/packages/generic/orders/1.0.0/orders-1.0.0.tgz");
            assert.strictEqual(stub.requests[0].headers["private-token"], "pat", "a configured token wins over the job token");
            assert.strictEqual(stub.requests[0].headers["job-token"], undefined);
        });
    } finally {
        stub.server.close();
    }
});

test("the token's variable and type are configurable: a deploy token goes in DEPLOY-TOKEN", async () => {
    const { archive, manifest } = packed();
    for (const [tokenType, header] of [[undefined, "private-token"], ["private", "private-token"], ["deploy", "deploy-token"], ["job", "job-token"]]) {
        const stub = await gitlab();
        try {
            await withEnv({ OTHER_TOKEN: "t0k" }, async () => {
                await publish(archive, manifest, "gitlab-package", { url: stub.api, project: "42", tokenEnv: "OTHER_TOKEN", tokenType });
                assert.strictEqual(stub.requests[0].headers[header], "t0k", String(tokenType));
            });
        } finally {
            stub.server.close();
        }
    }
});

test("without a credential, a project, or with an unknown token type, nothing is sent", async () => {
    const { archive, manifest } = packed();
    await withEnv({}, async () => {
        assert.throws(() => publish(archive, manifest, "gitlab-package", { url: "http://127.0.0.1:9/api/v4", project: "42" }),
            (e) => e instanceof ChannelError && /no token in \$GITLAB_TOKEN, which channels\.gitlab-package\.tokenEnv names, and no \$CI_JOB_TOKEN/.test(e.message));
        process.env.GITLAB_TOKEN = "pat";
        assert.throws(() => publish(archive, manifest, "gitlab-package", { url: "http://127.0.0.1:9/api/v4" }),
            (e) => e instanceof ChannelError && /needs a project: set channels\.gitlab-package\.project, or run in a GitLab CI job \(\$CI_PROJECT_ID\)/.test(e.message));
        assert.throws(() => publish(archive, manifest, "gitlab-package", { url: "http://127.0.0.1:9/api/v4", project: "42", tokenType: "oauth" }),
            (e) => e instanceof ChannelError && /tokenType must be private, deploy or job, not "oauth"/.test(e.message));
    });
});

test("without a URL or CI_API_V4_URL it publishes to gitlab.com", async () => {
    const { channelSettings } = require("../src/gitlab");
    await withEnv({ GITLAB_TOKEN: "pat" }, async () => {
        assert.strictEqual(channelSettings({ project: "42" }).url, "https://gitlab.com/api/v4");
    });
});

// ------------------------------------------------------------------ the same version again

test("a version GitLab already has is read back first: identical is left alone, different is refused, missing is uploaded", async () => {
    // GitLab allows duplicates by default: a PUT of different bytes would be accepted,
    // and served from then on. Only the read-back keeps a version immutable.
    const { archive, manifest, manifestBytes } = packed();
    const bytes = fs.readFileSync(archive);
    for (const [held, expected, puts] of [
        [{ [ARCHIVE]: bytes, [MANIFEST]: manifestBytes }, "same", []],
        [{ [ARCHIVE]: bytes }, "published", [MANIFEST]], // an earlier run stopped between the two files
        [{ [ARCHIVE]: Buffer.from("other") }, /already has orders-1\.0\.0\.tgz in orders 1\.0\.0, and it is not this one/, []],
        [{ [ARCHIVE]: bytes, [MANIFEST]: Buffer.from("{}") }, /already has manifest\.json in orders 1\.0\.0, and it is not this one/, []],
    ]) {
        const stub = await gitlab({ files: { ...held } });
        try {
            await withEnv({ GITLAB_TOKEN: "pat" }, async () => {
                const call = publish(archive, manifest, "gitlab-package", { url: stub.api, project: "42" });
                if (expected instanceof RegExp) {
                    await assert.rejects(call, (e) => e instanceof ChannelError && expected.test(e.message)
                        && /a published version is never replaced/.test(e.message), String(expected));
                } else {
                    assert.strictEqual((await call).alreadyPublished === true, expected === "same");
                }
                assert.deepStrictEqual(stub.requests.filter((r) => r.method === "PUT").map((r) => r.url.replace(/^\/api\/v4/, "")), puts);
            });
        } finally {
            stub.server.close();
        }
    }
});

test("a read-back that fails is reported, and nothing is uploaded over it", async () => {
    const { archive, manifest } = packed();
    for (const [status, expected] of [[500, /could not read orders-1\.0\.0\.tgz back from .*: 500/], [401, /Check the token/]]) {
        const stub = await gitlab({ readStatus: status });
        try {
            await withEnv({ GITLAB_TOKEN: "pat" }, async () => {
                await assert.rejects(publish(archive, manifest, "gitlab-package", { url: stub.api, project: "42" }),
                    (e) => e instanceof ChannelError && expected.test(e.message));
                assert.ok(!stub.requests.some((r) => r.method === "PUT"));
            });
        } finally {
            stub.server.close();
        }
    }
});

test("an upload GitLab refuses is read back: the same file published meanwhile is success, anything else is reported", async () => {
    // With Allow duplicates off GitLab refuses an existing file with a 400.
    const { archive, manifest } = packed();
    const bytes = fs.readFileSync(archive);
    for (const [refuse, expected] of [
        [{ url: ARCHIVE, status: 400, message: "400 Bad request - Duplicate package is not allowed", store: bytes }, "ok"],
        [{ url: ARCHIVE, status: 400, message: "400 Bad request - Duplicate package is not allowed", store: Buffer.from("x") }, /already has orders-1\.0\.0\.tgz/],
        [{ url: ARCHIVE, status: 403, message: "403 Forbidden" }, /GitLab refused orders-1\.0\.0\.tgz: 403 Forbidden\. Check the token/],
    ]) {
        const stub = await gitlab({ refuse });
        try {
            await withEnv({ GITLAB_TOKEN: "pat" }, async () => {
                const call = publish(archive, manifest, "gitlab-package", { url: stub.api, project: "42" });
                if (expected instanceof RegExp) {
                    await assert.rejects(call, (e) => e instanceof ChannelError && expected.test(e.message), String(expected));
                } else {
                    await call;
                }
            });
        } finally {
            stub.server.close();
        }
    }
});

test("package name and version are encoded into the URL; the name follows packageName", async () => {
    const { archive, manifest } = packed("orders", "2.1.0-rc.1+build.7");
    const stub = await gitlab();
    try {
        await withEnv({ GITLAB_TOKEN: "pat" }, async () => {
            await publish(archive, manifest, "gitlab-package", { url: stub.api, project: "42", packageName: "contract-{target}" });
            assert.strictEqual(stub.requests[0].url,
                "/api/v4/projects/42/packages/generic/contract-orders/2.1.0-rc.1%2Bbuild.7/orders-2.1.0-rc.1%2Bbuild.7.tgz");
        });
    } finally {
        stub.server.close();
    }
});

// ------------------------------------------------------------------ release: true

test("release: true creates the tag's release from the commit, linking both files as packages", async () => {
    const { archive, manifest } = packed();
    const stub = await gitlab();
    try {
        await withEnv({ GITLAB_TOKEN: "pat", CI_COMMIT_SHA: "abc123" }, async () => {
            await publish(archive, manifest, "gitlab-package", {
                url: stub.api, project: "42", release: true, title: "{target} contract {version}", description: "Contract for {target}.",
            });
            const created = stub.requests.find((r) => r.method === "POST");
            assert.strictEqual(created.url, "/api/v4/projects/42/releases");
            const body = JSON.parse(created.body);
            assert.strictEqual(body.tag_name, "orders-v1.0.0");
            assert.strictEqual(body.ref, "abc123");
            assert.strictEqual(body.name, "orders contract 1.0.0");
            assert.strictEqual(body.description, "Contract for orders.", "notes default to the description");
            assert.deepStrictEqual(body.assets.links, [
                { name: "orders-1.0.0.tgz", url: `${stub.api}${ARCHIVE}`, link_type: "package" },
                { name: "manifest.json", url: `${stub.api}${MANIFEST}`, link_type: "package" },
            ]);
        });
    } finally {
        stub.server.close();
    }
});

test("an existing release gets only the links it lacks; one that has them is left alone", async () => {
    const { archive, manifest, manifestBytes } = packed();
    const bytes = fs.readFileSync(archive);
    const stub = await gitlab({
        files: { [ARCHIVE]: bytes, [MANIFEST]: manifestBytes },
        releases: { "v-orders-1.0.0": { tag_name: "v-orders-1.0.0", assets: { links: [{ name: "manifest.json", url: "elsewhere" }] } } },
    });
    try {
        await withEnv({ GITLAB_TOKEN: "pat" }, async () => {
            const options = { url: stub.api, project: "42", release: true, tagFormat: "v-{target}-{version}", notes: "n" };
            await publish(archive, manifest, "gitlab-package", options);
            const posts = stub.requests.filter((r) => r.method === "POST");
            assert.deepStrictEqual(posts.map((r) => r.url), ["/api/v4/projects/42/releases/v-orders-1.0.0/assets/links"]);
            assert.deepStrictEqual(JSON.parse(posts[0].body), { name: "orders-1.0.0.tgz", url: `${stub.api}${ARCHIVE}`, link_type: "package" });

            stub.requests.length = 0;
            await publish(archive, manifest, "gitlab-package", options);
            assert.ok(!stub.requests.some((r) => r.method === "POST" || r.method === "PUT"), "nothing changes on a second run");
        });
    } finally {
        stub.server.close();
    }
});

test("a pre-release publishes the package but no release, so the latest release is never unfinished", async () => {
    const { archive, manifest } = packed("orders", "1.1.0-rc.1");
    const stub = await gitlab();
    try {
        await withEnv({ GITLAB_TOKEN: "pat", CI_COMMIT_SHA: "abc" }, async () => {
            const logged = [];
            await publish(archive, manifest, "gitlab-package", { url: stub.api, project: "42", release: true }, (m) => logged.push(m));
            assert.ok(stub.requests.some((r) => r.method === "PUT"));
            assert.ok(!stub.requests.some((r) => r.url.includes("/releases")));
            assert.ok(logged.some((m) => /no GitLab release for a pre-release/.test(m)), logged.join("\n"));
        });
    } finally {
        stub.server.close();
    }
});

test("a release's commit is the checkout's HEAD outside CI, and a release with neither is refused before anything is sent", async () => {
    const { dir, archive, manifest } = packed();
    const stub = await gitlab();
    try {
        await withEnv({ GITLAB_TOKEN: "pat" }, async () => {
            const options = { url: stub.api, project: "42", release: true, baseDir: dir };
            assert.throws(() => publish(archive, manifest, "gitlab-package", options),
                (e) => e instanceof ChannelError && /release: true needs the commit to tag: \$CI_COMMIT_SHA, or a git checkout/.test(e.message));
            assert.strictEqual(stub.requests.length, 0);

            const git = (...args) => execFileSync("git", ["-C", dir, ...args], { encoding: "utf8" }).trim();
            git("init", "-q");
            git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "--allow-empty", "-m", "c");
            await publish(archive, manifest, "gitlab-package", options);
            assert.strictEqual(JSON.parse(stub.requests.find((r) => r.method === "POST").body).ref, git("rev-parse", "HEAD"));
        });
    } finally {
        stub.server.close();
    }
});

test("a release GitLab refuses is reported with its answer", async () => {
    const { archive, manifest } = packed();
    const stub = await gitlab();
    stub.server.removeAllListeners("request");
    stub.server.on("request", (request, response) => {
        request.resume();
        request.on("end", () => {
            if (request.url.includes("/releases")) {
                response.writeHead(request.method === "GET" ? 403 : 500, { "Content-Type": "application/json" });
                return response.end(JSON.stringify({ message: "403 Forbidden" }));
            }
            response.writeHead(request.method === "GET" ? 404 : 201);
            response.end();
        });
    });
    try {
        await withEnv({ GITLAB_TOKEN: "pat", CI_COMMIT_SHA: "abc" }, async () => {
            await assert.rejects(publish(archive, manifest, "gitlab-package", { url: stub.api, project: "42", release: true }),
                (e) => e instanceof ChannelError && /could not read the GitLab release orders-v1\.0\.0: 403/.test(e.message));
        });
    } finally {
        stub.server.close();
    }
});

test("an answer that is not JSON, such as a proxy's error page, is reported as it came", async () => {
    const { archive, manifest } = packed();
    const server = http.createServer((request, response) => {
        request.resume();
        request.on("end", () => { response.writeHead(502, { "Content-Type": "text/html" }); response.end("<h1>Bad Gateway</h1>"); });
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    try {
        await withEnv({ GITLAB_TOKEN: "pat" }, async () => {
            await assert.rejects(
                publish(archive, manifest, "gitlab-package", { url: `http://127.0.0.1:${server.address().port}/api/v4`, project: "42" }),
                (e) => e instanceof ChannelError && /: 502 <h1>Bad Gateway<\/h1>\.$/.test(e.message));
        });
    } finally {
        server.close();
    }
});

test("an upload GitLab refuses, with no copy readable afterwards, is reported with GitLab's answer to the upload", async () => {
    const { archive, manifest } = packed();
    let put = false;
    const server = http.createServer((request, response) => {
        request.resume();
        request.on("end", () => {
            if (request.method === "PUT") put = true;
            const status = request.method === "PUT" ? 500 : (put ? 503 : 404);
            response.writeHead(status, { "Content-Type": "application/json" });
            response.end(JSON.stringify({ message: request.method === "PUT" ? "500 Internal Server Error" : "unavailable" }));
        });
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    try {
        await withEnv({ GITLAB_TOKEN: "pat" }, async () => {
            await assert.rejects(
                publish(archive, manifest, "gitlab-package", { url: `http://127.0.0.1:${server.address().port}/api/v4`, project: "42" }),
                (e) => e instanceof ChannelError && /^GitLab refused orders-1\.0\.0\.tgz: 500 Internal Server Error\.$/.test(e.message));
        });
    } finally {
        server.close();
    }
});

test("release: true with a deploy token is refused before anything is sent: GitLab gives a deploy token no Releases API", async () => {
    const { archive, manifest } = packed();
    const stub = await gitlab();
    try {
        await withEnv({ OTHER_TOKEN: "dt", CI_COMMIT_SHA: "abc" }, async () => {
            assert.throws(() => publish(archive, manifest, "gitlab-package",
                { url: stub.api, project: "42", tokenEnv: "OTHER_TOKEN", tokenType: "deploy", release: true }),
            (e) => e instanceof ChannelError
                && /a deploy token cannot create a GitLab release/.test(e.message)
                && /CI_JOB_TOKEN, or a personal or project access token/.test(e.message)
                && /release: false/.test(e.message));
            assert.strictEqual(stub.requests.length, 0);
            // A pre-release creates no release, so a deploy token is enough for it.
            const pre = packed("orders", "1.1.0-rc.1");
            await publish(pre.archive, pre.manifest, "gitlab-package",
                { url: stub.api, project: "42", tokenEnv: "OTHER_TOKEN", tokenType: "deploy", release: true });
        });
    } finally {
        stub.server.close();
    }
});

test("GitLab's answer is not repeated after a status it already starts with", async () => {
    const { archive, manifest } = packed();
    const stub = await gitlab({ readStatus: 404 });
    stub.server.removeAllListeners("request");
    stub.server.on("request", (request, response) => {
        request.resume();
        request.on("end", () => {
            response.writeHead(request.method === "GET" ? 404 : 403, { "Content-Type": "application/json" });
            response.end(JSON.stringify({ message: request.method === "GET" ? "404 Not Found" : "403 Forbidden" }));
        });
    });
    try {
        await withEnv({ GITLAB_TOKEN: "pat" }, async () => {
            await assert.rejects(publish(archive, manifest, "gitlab-package", { url: stub.api, project: "42" }),
                (e) => /GitLab refused orders-1\.0\.0\.tgz: 403 Forbidden\. Check/.test(e.message) && !/403 403/.test(e.message));
        });
    } finally {
        stub.server.close();
    }
});
