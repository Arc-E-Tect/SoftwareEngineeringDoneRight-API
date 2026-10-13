"use strict";

// API_ONLY_PUBLISHER_REMOTE: whether `publish` may contact anything. `never` keeps the
// Publisher -- the sealed image by default -- to the channels' local modes.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");

const { REMOTE_ENV, remoteMode, isRemote, refuseRemote } = require("../src/remote");
const { publish, ChannelError } = require("../src/channels");

test("the switch allows remote publishing when unset or empty, and refuses it with never", () => {
    assert.strictEqual(REMOTE_ENV, "API_ONLY_PUBLISHER_REMOTE");
    assert.strictEqual(remoteMode({}), "allow");
    assert.strictEqual(remoteMode({ [REMOTE_ENV]: "" }), "allow");
    assert.strictEqual(remoteMode({ [REMOTE_ENV]: "allow" }), "allow");
    assert.strictEqual(remoteMode({ [REMOTE_ENV]: "never" }), "never");
});

test("any other value of the switch is refused, naming the variable and what it accepts", () => {
    for (const value of ["NEVER", "no", "false", "0", " never"]) {
        assert.throws(() => remoteMode({ [REMOTE_ENV]: value }),
            (e) => e instanceof ChannelError
                && e.message.includes(`API_ONLY_PUBLISHER_REMOTE must be allow or never, not ${JSON.stringify(value)}`), value);
    }
});

test("a channel is remote when it would contact something: a URL, a registry, a release", () => {
    for (const [channel, settings, remote] of [
        ["file", { directory: "build/publish" }, false],
        ["maven", { groupId: "g", repository: "build/maven" }, false],
        ["maven", { groupId: "g" }, false], // ~/.m2/repository
        ["maven", { groupId: "g", repository: "https://maven.example.com/releases" }, true],
        ["maven", { groupId: "g", repository: "http://localhost:8081/repo" }, true],
        ["nuget", { idPrefix: "X", repository: "build/nuget" }, false],
        ["nuget", { idPrefix: "X", repository: "https://nuget.example.com/v3/index.json" }, true],
        ["npm", { publish: false }, false],
        ["npm", {}, true],
        ["npm", { publish: true }, true],
        ["github-release", { repository: "o/r" }, true],
    ]) {
        assert.strictEqual(isRemote(channel, settings), remote, `${channel} ${JSON.stringify(settings)}`);
    }
});

test("refusing names every remote channel a run would use, and the way out", () => {
    const configured = {
        file: { directory: "build/publish" },
        maven: { groupId: "g", repository: "https://maven.example.com" },
        "github-release": { repository: "o/r" },
    };
    const never = { [REMOTE_ENV]: "never" };
    assert.throws(() => refuseRemote(["file", "maven", "github-release"], configured, never), (e) => {
        assert.ok(e instanceof ChannelError);
        assert.match(e.message, /API_ONLY_PUBLISHER_REMOTE=never refuses the remote channels maven, github-release/);
        assert.match(e.message, /nothing was built, packed or sent/);
        assert.match(e.message, /API_ONLY_PUBLISHER_REMOTE=allow/);
        return true;
    });
    // Only the channels the run uses count, and allow refuses nothing.
    refuseRemote(["file"], configured, never);
    refuseRemote(["file", "maven", "github-release"], configured, {});
    // A channel the run names that apionly.yaml does not configure is judged on no settings.
    assert.throws(() => refuseRemote(["npm"], configured, never), /remote channels npm/);
});

test("the channels themselves refuse a remote mode under never, before contacting anything", async () => {
    // The Publisher used as a library goes through publish() too.
    const requests = [];
    const server = http.createServer((request, response) => { requests.push(request.url); response.writeHead(500); response.end(); });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const base = `http://127.0.0.1:${server.address().port}`;
    const saved = process.env[REMOTE_ENV];
    process.env[REMOTE_ENV] = "never";
    try {
        for (const [channel, settings] of [
            ["maven", { groupId: "g", repository: `${base}/repo` }],
            ["nuget", { idPrefix: "X", repository: `${base}/v3/index.json` }],
            ["npm", { registry: base }],
            ["github-release", { repository: "o/r" }],
        ]) {
            assert.throws(() => publish("/does/not/matter.tgz", { target: "t", version: "1.0.0" }, channel, settings),
                (e) => e instanceof ChannelError && new RegExp(`refuses the remote channels ${channel}`).test(e.message), channel);
        }
        assert.deepStrictEqual(requests, [], "nothing was contacted");

        // The local modes still work.
        const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-remote-"));
        const archive = path.join(dir, "t-1.0.0.tgz");
        fs.writeFileSync(archive, "archive");
        const result = publish(archive, { target: "t", version: "1.0.0" }, "file", { directory: path.join(dir, "out") });
        assert.ok(fs.existsSync(result.location));
    } finally {
        if (saved === undefined) delete process.env[REMOTE_ENV]; else process.env[REMOTE_ENV] = saved;
        server.close();
    }
});

test("the image is sealed by default: it refuses remote publishing unless a pipeline allows it", () => {
    const dockerfile = fs.readFileSync(path.join(__dirname, "..", "Dockerfile"), "utf8");
    assert.match(dockerfile, /API_ONLY_PUBLISHER_REMOTE=never/);
});
