"use strict";

// A deterministic ZIP writer, for the .nupkg the nuget channel builds.
//
// Node has no ZIP writer, and the Publisher carries one runtime dependency; zip, unlike
// tar, is not reliably installed on Windows or in slim CI images. The subset needed is
// small: a local header and deflated data per entry, a central directory, and an end
// record. No ZIP64, no encryption, no extra fields.
//
// Deterministic by construction, so that the same packed archive always yields the same
// package: entries are written in the order given, every entry carries the same fixed
// timestamp, and deflate runs at a fixed level.

const zlib = require("zlib");

class ZipError extends Error {}

// 1980-01-01 00:00:00, the earliest time a DOS date can hold.
const DOS_DATE = (0 << 9) | (1 << 5) | 1;
const DOS_TIME = 0;
const UTF8_NAMES = 0x0800;

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
});

function crc32(buffer) {
    let crc = 0xffffffff;
    for (const byte of buffer) crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
    return (crc ^ 0xffffffff) >>> 0;
}

/** Refuses a name that is empty, absolute, climbs out, uses backslashes, or repeats. */
function checkNames(entries) {
    const seen = new Set();
    for (const { name } of entries) {
        if (!name || name.startsWith("/") || name.includes("\\") || name.split("/").includes("..")) {
            throw new ZipError(`${JSON.stringify(name)} is not a valid entry name`);
        }
        if (seen.has(name)) throw new ZipError(`${JSON.stringify(name)} appears twice`);
        seen.add(name);
    }
}

/**
 * A ZIP archive of `entries`, in the order given.
 *
 * @param {Array<{name: string, data: Buffer}>} entries forward-slash paths and their bytes
 * @returns {Buffer}
 */
function zip(entries) {
    checkNames(entries);
    const locals = [];
    const centrals = [];
    let offset = 0;
    for (const { name, data } of entries) {
        const nameBytes = Buffer.from(name, "utf8");
        const deflated = zlib.deflateRawSync(data, { level: 9 });
        const crc = crc32(data);

        const local = Buffer.alloc(30);
        local.writeUInt32LE(0x04034b50, 0);
        local.writeUInt16LE(20, 4);
        local.writeUInt16LE(UTF8_NAMES, 6);
        local.writeUInt16LE(8, 8);
        local.writeUInt16LE(DOS_TIME, 10);
        local.writeUInt16LE(DOS_DATE, 12);
        local.writeUInt32LE(crc, 14);
        local.writeUInt32LE(deflated.length, 18);
        local.writeUInt32LE(data.length, 22);
        local.writeUInt16LE(nameBytes.length, 26);
        locals.push(local, nameBytes, deflated);

        const central = Buffer.alloc(46);
        central.writeUInt32LE(0x02014b50, 0);
        central.writeUInt16LE(20, 4);
        central.writeUInt16LE(20, 6);
        central.writeUInt16LE(UTF8_NAMES, 8);
        central.writeUInt16LE(8, 10);
        central.writeUInt16LE(DOS_TIME, 12);
        central.writeUInt16LE(DOS_DATE, 14);
        central.writeUInt32LE(crc, 16);
        central.writeUInt32LE(deflated.length, 20);
        central.writeUInt32LE(data.length, 24);
        central.writeUInt16LE(nameBytes.length, 28);
        central.writeUInt32LE(offset, 42);
        centrals.push(central, nameBytes);

        offset += local.length + nameBytes.length + deflated.length;
    }
    const directory = Buffer.concat(centrals);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0);
    end.writeUInt16LE(entries.length, 8);
    end.writeUInt16LE(entries.length, 10);
    end.writeUInt32LE(directory.length, 12);
    end.writeUInt32LE(offset, 16);
    return Buffer.concat([...locals, directory, end]);
}

module.exports = { zip, crc32, ZipError };
