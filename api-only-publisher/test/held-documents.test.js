"use strict";

// What a destination holds under a version, told apart by its documents rather than its
// bytes: the manifest's `files`, the same hashes a subscriber locks. Two packs of the same
// documents from different commits differ in `source` and `producedAt`, and are the same
// contract.

const test = require("node:test");
const assert = require("node:assert");
const zlib = require("node:zlib");

const { tgz, untgz, TarError } = require("../src/tar");
const { zip, unzip, ZipError } = require("../src/zip");
const { manifestIn, sameDocuments, judgeHeld, HeldError } = require("../src/held-documents");

const manifest = (overrides = {}) => ({
    schemaVersion: 1, target: "orders", version: "1.0.0", producedAt: "2026-01-01T00:00:00Z", closureSha256: "c",
    source: { repository: "https://example.com/r.git", commit: "a".repeat(40) },
    files: [{ path: "asyncapi.yaml", sha256: "1".repeat(64) }, { path: "openapi.yaml", sha256: "2".repeat(64) }],
    ...overrides,
});
const json = (data) => Buffer.from(JSON.stringify(data, null, 2) + "\n");

// ------------------------------------------------------------------ readers

test("untgz reads back every entry tgz writes, in order", () => {
    const entries = [{ name: "a.yaml", data: Buffer.from("a: 1\n") }, { name: "empty", data: Buffer.alloc(0) },
                     { name: "big.yaml", data: Buffer.alloc(1300, 0x61) }];
    assert.deepStrictEqual(untgz(tgz(entries, 0)), entries);
});

test("untgz reads a ustar prefix and skips directories and pax headers, as npm's tarballs have them", () => {
    const block = (name, type, data = Buffer.alloc(0), prefix = "") => {
        const header = Buffer.alloc(512, 0);
        header.write(name, 0);
        header.write(data.length.toString(8).padStart(11, "0") + "\0", 124, "ascii");
        header.write(type, 156, "ascii");
        header.write("ustar\0", 257, "ascii");
        header.write(prefix, 345);
        const padding = Buffer.alloc((512 - (data.length % 512)) % 512, 0);
        return Buffer.concat([header, data, padding]);
    };
    const archive = zlib.gzipSync(Buffer.concat([
        block("package", "5"),
        block("PaxHeader", "x", Buffer.from("20 path=package/x\n")),
        block("manifest.json", "0", Buffer.from("{}"), "package"),
        block("old-style", "\0", Buffer.from("x")),
        Buffer.alloc(1024, 0),
    ]));
    assert.deepStrictEqual(untgz(archive).map((e) => [e.name, e.data.toString()]),
        [["package/manifest.json", "{}"], ["old-style", "x"]]);
});

test("untgz refuses what is not a gzipped tar archive, and one that ends inside an entry", () => {
    assert.throws(() => untgz(Buffer.from("not gzip")), (e) => e instanceof TarError && /not a gzipped tar archive/.test(e.message));
    const whole = zlib.gunzipSync(tgz([{ name: "a.yaml", data: Buffer.alloc(600, 0x61) }], 0));
    assert.throws(() => untgz(zlib.gzipSync(whole.subarray(0, 700))), (e) => e instanceof TarError && /ends inside a\.yaml/.test(e.message));
});

test("unzip reads back every entry zip writes, and checks each one's CRC-32", () => {
    const entries = [{ name: "a.nuspec", data: Buffer.from("<package/>") }, { name: "contracts/manifest.json", data: Buffer.from("{}") }];
    const bytes = zip(entries);
    assert.deepStrictEqual(unzip(bytes), entries);
    const corrupt = Buffer.from(bytes);
    const name = Buffer.from("contracts/manifest.json");
    corrupt[corrupt.indexOf(name) + name.length] ^= 0xff; // the entry's first deflated byte
    assert.throws(() => unzip(corrupt), (e) => e instanceof ZipError);
    assert.throws(() => unzip(Buffer.from("not a zip")), (e) => e instanceof ZipError && /not a ZIP archive/.test(e.message));
});

test("unzip refuses an entry whose local header is missing, and a compression method it does not read", () => {
    const bytes = zip([{ name: "a.txt", data: Buffer.from("a") }]);
    const noLocal = Buffer.from(bytes);
    noLocal.writeUInt32LE(0, 0);
    assert.throws(() => unzip(noLocal), (e) => e instanceof ZipError && /no local header for a\.txt/.test(e.message));
    const bzip2 = Buffer.from(bytes);
    bzip2.writeUInt16LE(12, bzip2.lastIndexOf(Buffer.from([0x50, 0x4b, 0x01, 0x02])) + 10);
    assert.throws(() => unzip(bzip2), (e) => e instanceof ZipError && /a\.txt is compressed with method 12/.test(e.message));
    const truncated = Buffer.from(bytes);
    truncated.writeUInt32LE(0xffff, truncated.length - 22 + 16);
    assert.throws(() => unzip(truncated), (e) => e instanceof ZipError && /no central directory entry/.test(e.message));
});

// ------------------------------------------------------------------ the held manifest

test("manifestIn finds the manifest in a packed archive, an npm package and a NuGet package, or in the file itself", () => {
    const held = manifest();
    for (const [label, bytes] of [
        ["archive", tgz([{ name: "manifest.json", data: json(held) }, { name: "openapi.yaml", data: Buffer.from("x") }], 0)],
        ["nupkg", zip([{ name: "a.nuspec", data: Buffer.from("x") }, { name: "contracts/manifest.json", data: json(held) }])],
        ["manifest.json", json(held)],
    ]) {
        assert.deepStrictEqual(manifestIn(bytes), held, label);
    }
    // npm writes its entries under package/; built by hand, as tgz takes plain names only.
    const header = Buffer.alloc(512, 0);
    const data = json(held);
    header.write("manifest.json", 0);
    header.write(data.length.toString(8).padStart(11, "0") + "\0", 124, "ascii");
    header.write("0", 156, "ascii");
    header.write("ustar\0", 257, "ascii");
    header.write("package", 345);
    const npm = zlib.gzipSync(Buffer.concat([header, data, Buffer.alloc((512 - (data.length % 512)) % 512), Buffer.alloc(1024)]));
    assert.deepStrictEqual(manifestIn(npm), held, "npm");
});

test("manifestIn refuses a package with no manifest, or one that is not a manifest", () => {
    for (const [bytes, expected] of [
        [tgz([{ name: "openapi.yaml", data: Buffer.from("x") }], 0), /holds no manifest\.json/],
        [tgz([{ name: "manifest.json", data: Buffer.from("not json") }], 0), /manifest\.json is not JSON/],
        [json({ target: "orders" }), /lists no files/],
        [Buffer.from("plain text"), /neither a manifest nor a package/],
    ]) {
        assert.throws(() => manifestIn(bytes), (e) => e instanceof HeldError && expected.test(e.message), String(expected));
    }
});

test("sameDocuments compares the documents and their hashes, and nothing else", () => {
    const mine = manifest();
    assert.strictEqual(sameDocuments(manifest({ producedAt: "2027-01-01T00:00:00Z", source: { commit: "b".repeat(40) },
                                                closureSha256: "other" }), mine), true, "another commit, the same documents");
    assert.strictEqual(sameDocuments(manifest({ files: [mine.files[1], mine.files[0]] }), mine), true, "in any order");
    assert.strictEqual(sameDocuments(manifest({ files: [mine.files[0], { path: "openapi.yaml", sha256: "3".repeat(64) }] }), mine),
        false, "a document changed");
    assert.strictEqual(sameDocuments(manifest({ files: [mine.files[1]] }), mine), false, "a document removed");
    assert.strictEqual(sameDocuments(manifest({ files: [...mine.files, { path: "x.yaml", sha256: "4".repeat(64) }] }), mine),
        false, "a document added");
    assert.strictEqual(sameDocuments(manifest({ version: "1.0.1" }), mine), false, "another version");
    assert.strictEqual(sameDocuments(manifest({ target: "invoices" }), mine), false, "another target");
});

test("judgeHeld says whether a copy is the same version, another one, or cannot be compared", () => {
    const mine = manifest();
    const same = judgeHeld(json(manifest({ producedAt: "2027-01-01T00:00:00Z" })), "json", mine);
    assert.strictEqual(same.verdict, "same");
    assert.strictEqual(same.held.manifest.producedAt, "2027-01-01T00:00:00Z");
    assert.deepStrictEqual(judgeHeld(json(manifest({ files: [] })), "json", mine), { verdict: "different" });
    assert.deepStrictEqual(judgeHeld(Buffer.from("garbage"), "zip", mine),
        { verdict: "unreadable", reason: "not a ZIP archive: it has no end of central directory record" });
    // Cut short, a package can fail as an out-of-range read rather than a ZipError: unreadable all the same.
    const cut = zip([{ name: "contracts/manifest.json", data: json(mine) }]);
    assert.strictEqual(judgeHeld(cut.subarray(cut.length - 30), "zip", mine).verdict, "unreadable");
});
