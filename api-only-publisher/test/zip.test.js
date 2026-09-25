"use strict";

const test = require("node:test");
const assert = require("node:assert");

const { zip, ZipError } = require("../src/zip");
const { readZip } = require("./zip-reader");

const entries = () => [
    { name: "b.txt", data: Buffer.from("second\n") },
    { name: "dir/a.yaml", data: Buffer.from("openapi: 3.1.1\n".repeat(50)) },
    { name: "empty", data: Buffer.alloc(0) },
];

test("every entry reads back byte for byte, in the order it was given", () => {
    const read = readZip(zip(entries()));

    assert.deepStrictEqual(read.map((e) => e.name), ["b.txt", "dir/a.yaml", "empty"]);
    for (const [i, entry] of entries().entries()) {
        assert.ok(read[i].data.equals(entry.data), entry.name);
    }
});

test("the same entries always produce the same bytes", () => {
    assert.ok(zip(entries()).equals(zip(entries())));
});

test("every entry is deflated and carries the fixed timestamp 1980-01-01 00:00", () => {
    for (const entry of readZip(zip(entries()))) {
        assert.strictEqual(entry.method, 8, entry.name);
        assert.strictEqual(entry.date, (0 << 9) | (1 << 5) | 1, entry.name);
        assert.strictEqual(entry.time, 0, entry.name);
    }
});

test("a name that would escape the archive, or appear twice, is refused", () => {
    for (const name of ["", "/abs.txt", "a/../b.txt", "a\\b.txt"]) {
        assert.throws(() => zip([{ name, data: Buffer.from("x") }]),
            (e) => e instanceof ZipError && e.message.includes(JSON.stringify(name)), name);
    }
    assert.throws(() => zip([{ name: "a", data: Buffer.alloc(0) }, { name: "a", data: Buffer.alloc(0) }]),
        (e) => e instanceof ZipError && /twice/.test(e.message));
});

test("a non-ASCII name is written as UTF-8 and flagged as such", () => {
    const buffer = zip([{ name: "café.yaml", data: Buffer.from("x") }]);

    assert.strictEqual(readZip(buffer)[0].name, "café.yaml");
    assert.strictEqual(buffer.readUInt16LE(6) & 0x0800, 0x0800);
});
