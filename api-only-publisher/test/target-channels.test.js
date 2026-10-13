"use strict";

// #84: a target names the channels it is published to, and a channel may have any
// kebab-case name, with `type` saying which kind it is -- so two Maven repositories,
// one per partner, are two channels.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { load, ConfigError } = require("../src/config");
const { main } = require("../src/cli");
const { publish } = require("../src/channels");
const { HANDOFF_JSON } = require("../src/handoff");
const { prepare } = require("../src/pipeline");

const TARGETS = `targets:
  orders:
    openapi:
      bundle: bundles/orders.yaml
  payments:
    openapi:
      bundle: bundles/payments.yaml
`;

/** A library with `channels` and `targets` as given, its documents built at 1.0.0. */
function library(channels, targets = TARGETS) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-routes-"));
    fs.writeFileSync(path.join(dir, "apionly.yaml"),
        "schemaVersion: 1\nsources:\n  root: specs\n  openapi: openapi\ndefaults:\n  openapi:\n    outputName: openapi.yaml\n" +
        "build:\n  dist: dist\n" + channels + targets);
    for (const target of ["orders", "payments"]) {
        fs.mkdirSync(path.join(dir, "specs", "openapi", "bundles"), { recursive: true });
        fs.writeFileSync(path.join(dir, "specs", "openapi", "bundles", `${target}.yaml`), "openapi: 3.1.1\npaths: {}\n");
        fs.writeFileSync(path.join(dir, "specs", "openapi", "bundles", `${target}.bundle.properties`), "version=1.0.0\n");
        fs.mkdirSync(path.join(dir, "dist", target), { recursive: true });
        fs.writeFileSync(path.join(dir, "dist", target, "openapi.yaml"), "openapi: 3.1.1\ninfo:\n  title: T\n  version: 1.0.0\npaths: {}\n");
    }
    // What build stages, which the closure recorded in each archive is computed over.
    try { prepare(load(path.join(dir, "apionly.yaml")), { kinds: ["openapi"] }); } catch { /* a configuration a test expects refused */ }
    return dir;
}

const config = (dir) => load(path.join(dir, "apionly.yaml"));
const refused = (channels, targets, pattern) =>
    assert.throws(() => config(library(channels, targets)), (e) => e instanceof ConfigError && pattern.test(e.message), String(pattern));

async function quietly(argv) {
    const printed = [];
    const saved = console.log;
    console.log = (m) => printed.push(String(m));
    try {
        return { code: await main(argv), printed };
    } finally {
        console.log = saved;
    }
}

async function withEnv(values, body) {
    const saved = Object.fromEntries(Object.keys(values).map((k) => [k, process.env[k]]));
    for (const [k, v] of Object.entries(values)) if (v === undefined) delete process.env[k]; else process.env[k] = v;
    try {
        return await body();
    } finally {
        for (const [k, v] of Object.entries(saved)) if (v === undefined) delete process.env[k]; else process.env[k] = v;
    }
}

// ------------------------------------------------------------------ named channels

test("a channel named after a type is that type; any other name says its type", () => {
    const c = config(library(
        "channels:\n  file: { directory: out/file }\n  partner-a: { type: maven, groupId: a.b, repository: out/a }\n" +
        "  partner-b:\n    type: maven\n    groupId: a.b\n    repository: out/b\n  gitlab-package:\n"));
    assert.strictEqual(c.channelType("file"), "file");
    assert.strictEqual(c.channelType("partner-a"), "maven");
    assert.strictEqual(c.channelType("partner-b"), "maven");
    assert.strictEqual(c.channelType("gitlab-package"), "gitlab-package", "an empty channel is that type, with no settings");
});

test("a channel's name and type are checked when apionly.yaml is read", () => {
    refused("channels:\n  partner-a: { groupId: a.b }\n", TARGETS,
        /channel 'partner-a' is not a channel type; say which one it is with type: file, maven, npm, github-release, nuget or gitlab-package/);
    refused("channels:\n  partner-a: { type: smoke-signal }\n", TARGETS,
        /channels\.partner-a\.type 'smoke-signal' is not a channel type: file, maven, npm, github-release, nuget or gitlab-package/);
    refused("channels:\n  npm: { type: maven, groupId: a.b }\n", TARGETS,
        /channel 'npm' is named after a channel type, so its type is npm, not maven; give it another name/);
    for (const name of ["Partner_A", "partner--a", "1partner", "partner.a"]) {
        refused(`channels:\n  ${name}: { type: file }\n`, TARGETS,
            new RegExp(`channel name '${name.replace(".", "\\.")}': use lowercase letters and digits, in words joined by single hyphens`));
    }
    refused("channels:\n  partner-a: maven\n", TARGETS, /channels\.partner-a must be a mapping of settings/);
    refused("channels:\n  - file\n  - maven\n", TARGETS, /channels must be a mapping of channel names to their settings/);
});

// ------------------------------------------------------------------ a target's channels

test("a target is published to the channels it lists, and to every configured channel when it lists none", () => {
    const c = config(library("channels:\n  file: { directory: out }\n  partner-a: { type: maven, groupId: a.b, repository: m }\n  npm: { publish: false }\n",
        TARGETS.replace("  orders:\n", "  orders:\n    channels: [partner-a, npm]\n")));
    assert.deepStrictEqual(c.channelsOf("orders"), ["partner-a", "npm"]);
    assert.deepStrictEqual(c.channelsOf("payments"), ["file", "partner-a", "npm"]);
});

test("a target's channels are checked when apionly.yaml is read", () => {
    const channels = "channels:\n  file: { directory: out }\n";
    refused(channels, TARGETS.replace("  orders:\n", "  orders:\n    channels: [file, maven]\n"),
        /target 'orders' lists channel 'maven', which apionly\.yaml does not configure; it configures file/);
    refused(channels, TARGETS.replace("  orders:\n", "  orders:\n    channels: []\n"),
        /targets\.orders\.channels is empty; a target that is published nowhere says so with publish: false/);
    refused(channels, TARGETS.replace("  orders:\n", "  orders:\n    channels: file\n"),
        /targets\.orders\.channels must be a list of channel names/);
    refused(channels, TARGETS.replace("  orders:\n", "  orders:\n    channels: [file, file]\n"),
        /target 'orders' lists channel 'file' twice/);
    refused(channels, TARGETS.replace("  orders:\n", "  orders:\n    publish: false\n    channels: [file]\n"),
        /target 'orders' is not published \(publish: false\), so it has no channels/);
    refused(channels, TARGETS + "  landscape:\n    channels: [file]\n    openapi:\n      aggregate: [orders, payments]\n",
        /target 'landscape' is an aggregate, which is never published, so it has no channels/);
});

// ------------------------------------------------------------------ dispatch by type

test("a channel is dispatched by its type, with its own settings", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-dispatch-"));
    const archive = path.join(dir, "orders-1.0.0.tgz");
    fs.writeFileSync(archive, "archive");
    const manifest = { target: "orders", version: "1.0.0" };
    const a = publish(archive, manifest, "partner-a", { type: "file", directory: path.join(dir, "a") });
    const b = publish(archive, manifest, "partner-b", { type: "file", directory: path.join(dir, "b") });
    assert.ok(fs.existsSync(a.location) && a.location.startsWith(path.join(dir, "a")));
    assert.ok(fs.existsSync(b.location) && b.location.startsWith(path.join(dir, "b")));
    // A channel named after its type needs no `type`, as before.
    assert.ok(fs.existsSync(publish(archive, manifest, "file", { directory: path.join(dir, "c") }).location));
    assert.throws(() => publish(archive, manifest, "partner-c", {}), /unknown channel 'partner-c'/);
});

// ------------------------------------------------------------------ publish

const PARTNERS = "channels:\n" +
    "  partner-a: { type: file, directory: out/partner-a }\n" +
    "  partner-b: { type: file, directory: out/partner-b }\n" +
    "  internal: { type: maven, groupId: com.example, repository: out/m2 }\n";
const ROUTED = TARGETS
    .replace("  orders:\n", "  orders:\n    channels: [partner-a, internal]\n")
    .replace("  payments:\n", "  payments:\n    channels: [partner-b, internal]\n");

test("publish ships each target to its own channels, two of one type to two places, packed once", async () => {
    const dir = library(PARTNERS, ROUTED);
    const { printed } = await quietly(["publish", "-C", dir]);
    const has = (p) => fs.existsSync(path.join(dir, p));
    assert.ok(has("out/partner-a/orders/1.0.0/orders-1.0.0.tgz"));
    assert.ok(!has("out/partner-a/payments"), "payments is not routed to partner-a");
    assert.ok(has("out/partner-b/payments/1.0.0/payments-1.0.0.tgz"));
    assert.ok(!has("out/partner-b/orders"), "orders is not routed to partner-b");
    assert.ok(has("out/m2/com/example/orders/1.0.0/orders-1.0.0.tgz"));
    assert.ok(has("out/m2/com/example/payments/1.0.0/payments-1.0.0.tgz"));
    assert.strictEqual(printed.filter((m) => /^-- Packed /.test(m)).length, 2, "each target packed once");
    assert.ok(fs.readFileSync(path.join(dir, "out/partner-a/orders/1.0.0/orders-1.0.0.tgz"))
        .equals(fs.readFileSync(path.join(dir, "out/m2/com/example/orders/1.0.0/orders-1.0.0.tgz"))), "the same bytes everywhere");
});

test("the hand-off names each file's channel, and its type", async () => {
    const dir = library(PARTNERS, ROUTED);
    await quietly(["publish", "-C", dir, "-q"]);
    const listed = JSON.parse(fs.readFileSync(path.join(dir, "build", "packages", HANDOFF_JSON), "utf8")).files;
    const seen = [...new Set(listed.map((f) => `${f.target} ${f.channel} ${f.type}`))].sort();
    assert.deepStrictEqual(seen, [
        "orders internal maven", "orders partner-a file", "payments internal maven", "payments partner-b file",
    ]);
});

test("--channel narrows each target's channels, reporting a target it skips", async () => {
    const dir = library(PARTNERS, ROUTED);
    const { printed } = await quietly(["publish", "--channel", "partner-a", "-C", dir]);
    assert.ok(fs.existsSync(path.join(dir, "out/partner-a/orders/1.0.0/orders-1.0.0.tgz")));
    assert.ok(!fs.existsSync(path.join(dir, "out/m2")), "internal is not in this run");
    assert.ok(printed.includes("payments: not published to partner-a (not in targets.payments.channels)"), printed.join("\n"));
});

test("--channel that leaves nothing to publish fails, saying why", async () => {
    const dir = library(PARTNERS, ROUTED);
    await assert.rejects(main(["publish", "--channel", "partner-b", "--target", "orders", "-C", dir, "-q"]),
        /nothing was published: no target in this run is routed to partner-b/);
    assert.ok(!fs.existsSync(path.join(dir, "build", "packages")), "nothing was packed");
});

test("the sealed switch looks at the channels each target is actually published to, by type", async () => {
    const remote = "channels:\n  file: { directory: out/file }\n  partner-a: { type: maven, groupId: a.b, repository: https://maven.example.invalid/a }\n";
    const dir = library(remote, TARGETS.replace("  orders:\n", "  orders:\n    channels: [partner-a]\n")
        .replace("  payments:\n", "  payments:\n    channels: [file]\n"));
    await withEnv({ API_ONLY_PUBLISHER_REMOTE: "never" }, async () => {
        await assert.rejects(main(["publish", "-C", dir, "-q"]), /refuses the remote channels partner-a/);
        // payments alone goes only to file: nothing remote, so it is not refused.
        assert.strictEqual((await quietly(["publish", "--target", "payments", "-C", dir, "-q"])).code, 0);
        assert.ok(fs.existsSync(path.join(dir, "out/file/payments/1.0.0/payments-1.0.0.tgz")));
    });
});

test("an unconfigured --channel named after a type still works with no settings, as before", async () => {
    const dir = library("channels:\n  file: { directory: out/file }\n");
    await withEnv({ API_ONLY_PUBLISHER_REMOTE: "never" }, async () => {
        await assert.rejects(main(["publish", "--channel", "github-release", "-C", dir, "-q"]),
            /refuses the remote channels github-release/);
    });
    await assert.rejects(main(["publish", "--channel", "partner-z", "-C", dir, "-q"]),
        /--channel 'partner-z' is not a configured channel; apionly\.yaml configures file/);
});

// ------------------------------------------------------------------ targets

test("targets shows each published target's channels", async () => {
    const dir = library(PARTNERS, ROUTED);
    const { printed } = await quietly(["targets", "-C", dir]);
    assert.deepStrictEqual(printed, [
        "orders  [openapi]  channels: partner-a, internal",
        "payments  [openapi]  channels: partner-b, internal",
    ]);
});
