"use strict";

// A minimal ZIP reader, for the tests only: it reads what src/zip.js writes, from the
// central directory, and checks each entry's CRC-32 against its inflated bytes.

const zlib = require("node:zlib");

function crc32(buffer) {
    let crc = 0xffffffff;
    for (const byte of buffer) {
        crc ^= byte;
        for (let k = 0; k < 8; k++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
    return (crc ^ 0xffffffff) >>> 0;
}

/** Every entry, in central-directory order: {name, data, method, time, date}. */
function readZip(buffer) {
    const eocd = buffer.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
    if (eocd < 0) throw new Error("no end of central directory record");
    const count = buffer.readUInt16LE(eocd + 10);
    let offset = buffer.readUInt32LE(eocd + 16);
    const entries = [];
    for (let i = 0; i < count; i++) {
        if (buffer.readUInt32LE(offset) !== 0x02014b50) throw new Error(`bad central header at ${offset}`);
        const method = buffer.readUInt16LE(offset + 10);
        const time = buffer.readUInt16LE(offset + 12);
        const date = buffer.readUInt16LE(offset + 14);
        const crc = buffer.readUInt32LE(offset + 16);
        const compressed = buffer.readUInt32LE(offset + 20);
        const nameLength = buffer.readUInt16LE(offset + 28);
        const extraLength = buffer.readUInt16LE(offset + 30);
        const commentLength = buffer.readUInt16LE(offset + 32);
        const local = buffer.readUInt32LE(offset + 42);
        const name = buffer.toString("utf8", offset + 46, offset + 46 + nameLength);

        if (buffer.readUInt32LE(local) !== 0x04034b50) throw new Error(`bad local header for ${name}`);
        const start = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28);
        const raw = buffer.subarray(start, start + compressed);
        const data = method === 8 ? zlib.inflateRawSync(raw) : Buffer.from(raw);
        if (crc32(data) !== crc) throw new Error(`CRC mismatch for ${name}`);

        entries.push({ name, data, method, time, date });
        offset += 46 + nameLength + extraLength + commentLength;
    }
    return entries;
}

module.exports = { readZip };
