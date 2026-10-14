"use strict";

// A reproducible .tgz writer, for the archive `pack` produces.
//
// The system tar records each file's modification time, owner and permissions, and gzip
// records when it ran, so packing the same documents twice gave two archives. GNU and BSD
// tar do not agree on the flags that would pin those, so the archive is written here: a
// ustar header per file, with a fixed mode, no owner, and a modification time the caller
// chooses, then gzip with no timestamp and a fixed operating-system byte.

const zlib = require("zlib");

class TarError extends Error {}

const BLOCK = 512;

function octal(value, length) {
    return value.toString(8).padStart(length - 1, "0") + "\0";
}

function header(name, size, mtime) {
    const nameBytes = Buffer.from(name, "utf8");
    if (nameBytes.length > 100 || name.includes("/") || name === "" || name === "." || name === "..") {
        throw new TarError(`${JSON.stringify(name)} cannot be an entry of the archive: a plain file name of at most 100 bytes`);
    }
    const block = Buffer.alloc(BLOCK, 0);
    nameBytes.copy(block, 0);
    block.write(octal(0o644, 8), 100, "ascii");
    block.write(octal(0, 8), 108, "ascii");
    block.write(octal(0, 8), 116, "ascii");
    block.write(octal(size, 12), 124, "ascii");
    block.write(octal(mtime, 12), 136, "ascii");
    block.write("        ", 148, "ascii");
    block.write("0", 156, "ascii");
    block.write("ustar\0", 257, "ascii");
    block.write("00", 263, "ascii");
    let sum = 0;
    for (const byte of block) sum += byte;
    block.write(octal(sum, 7) + " ", 148, "ascii");
    return block;
}

/**
 * A gzipped ustar archive of `entries`, in the order given, every entry with mode 0644,
 * owner 0 and modification time `mtime`. The same arguments always give the same bytes.
 *
 * @param {Array<{name: string, data: Buffer}>} entries plain file names and their bytes
 * @param {number} mtime seconds since the epoch
 * @returns {Buffer}
 */
function tgz(entries, mtime) {
    const parts = [];
    for (const { name, data } of entries) {
        parts.push(header(name, data.length, mtime), data);
        const padding = (BLOCK - (data.length % BLOCK)) % BLOCK;
        if (padding) parts.push(Buffer.alloc(padding, 0));
    }
    parts.push(Buffer.alloc(BLOCK * 2, 0));
    const gzipped = zlib.gzipSync(Buffer.concat(parts), { level: 9 });
    // zlib writes the operating system it was built for into byte 9; 255 is "unknown",
    // so an archive packed on Windows is the same as one packed on Linux.
    gzipped[9] = 0xff;
    return gzipped;
}

function text(block, start, length) {
    const end = block.indexOf(0, start);
    return block.toString("utf8", start, end < 0 || end > start + length ? start + length : end);
}

/**
 * The regular files in a gzipped tar archive, in order: what `tgz` writes, and what npm
 * packs, with a ustar prefix joined to the name. Directories, links and pax headers are
 * skipped.
 *
 * @param {Buffer} bytes
 * @returns {Array<{name: string, data: Buffer}>}
 */
function untgz(bytes) {
    let tar;
    try {
        tar = zlib.gunzipSync(bytes);
    } catch (error) {
        throw new TarError(`not a gzipped tar archive: ${error.message}`);
    }
    const entries = [];
    for (let offset = 0; offset + BLOCK <= tar.length;) {
        const block = tar.subarray(offset, offset + BLOCK);
        if (block.every((byte) => byte === 0)) break;
        const prefix = block.toString("ascii", 257, 262) === "ustar" ? text(block, 345, 155) : "";
        const name = prefix ? `${prefix}/${text(block, 0, 100)}` : text(block, 0, 100);
        const size = parseInt(text(block, 124, 12).trim() || "0", 8);
        const start = offset + BLOCK;
        if (start + size > tar.length) throw new TarError(`the archive ends inside ${name}`);
        const type = String.fromCharCode(block[156]);
        if (type === "0" || type === "\0") entries.push({ name, data: Buffer.from(tar.subarray(start, start + size)) });
        offset = start + Math.ceil(size / BLOCK) * BLOCK;
    }
    return entries;
}

module.exports = { tgz, untgz, TarError };
