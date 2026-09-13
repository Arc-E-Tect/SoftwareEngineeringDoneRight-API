"use strict";

// Placeholder substitution.
//
// A {{token}} in a staged YAML file is replaced by the contents of <token>.md.
// Three choices here are deliberate:
//
//   1. Search scope. <token>.md is searched for from the root the caller names,
//      the staged source root by default, not from the directory of the file
//      holding the token, so a snippet can live anywhere under that root.
//   2. Failure mode. An unresolved placeholder is an error by default. Leaving a
//      marker in the document and carrying on would let a broken contract ship
//      from a green build.
//   3. Token grammar. Names may contain '-' and '.', so {{status-codes}} is a
//      placeholder like any other, never text silently left in the output.

const fs = require("fs");
const path = require("path");

// {{name}} where name may contain letters, digits, underscore, dash or dot.
// Deliberately no whitespace tolerance inside the braces: that would make a
// stray "{{ " in prose look like a placeholder.
const TOKEN = /\{\{([A-Za-z0-9_.-]+)\}\}/g;

class PlaceholderError extends Error {}

// Depth-first search for `fileName` under `startDir`. Directory entries are
// sorted so the result cannot depend on filesystem ordering, which would make
// builds non-reproducible across machines.
function findFile(startDir, fileName) {
    let entries;
    try {
        entries = fs.readdirSync(startDir, { withFileTypes: true });
    } catch {
        return null;
    }
    entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));

    for (const entry of entries) {
        if (entry.isFile() && entry.name === fileName) return path.join(startDir, entry.name);
    }
    for (const entry of entries) {
        if (entry.isDirectory()) {
            const found = findFile(path.join(startDir, entry.name), fileName);
            if (found) return found;
        }
    }
    return null;
}

// Keep the YAML indentation the placeholder sits at: the first line continues
// the indentation already present before the token, and every subsequent
// non-blank line gets that same indent prefixed, so multi-line Markdown stays
// valid inside an indented YAML scalar.
function indentReplacement(content, fullString, offset) {
    const lineStart = fullString.lastIndexOf("\n", offset - 1) + 1;
    const indent = fullString.slice(lineStart, offset).match(/^\s*/)[0];
    return content
        .replace(/\n$/, "")
        .split("\n")
        .map((line, i) => (i === 0 || line.length === 0 ? line : indent + line))
        .join("\n");
}

/**
 * Substitute {{token}} placeholders in `text`.
 *
 * @param {string} text          the content to substitute into
 * @param {object} options
 * @param {string} options.searchRoot  directory to search for <token>.md
 * @param {boolean} [options.strict]   throw on an unresolved token (default true)
 * @param {string} [options.describeAs] label used in error messages
 * @returns {{content: string, resolved: string[], unresolved: string[]}}
 */
function substitute(text, { searchRoot, strict = true, describeAs = "input" } = {}) {
    if (!searchRoot) throw new PlaceholderError("substitute() requires a searchRoot");

    const resolved = [];
    const unresolved = [];

    const content = text.replace(TOKEN, (match, token, offset, fullString) => {
        const mdFile = findFile(searchRoot, `${token}.md`);
        if (!mdFile) {
            unresolved.push(token);
            return match; // leave it visible; strict mode turns it into an error
        }
        resolved.push(token);
        return indentReplacement(fs.readFileSync(mdFile, "utf8"), fullString, offset);
    });

    if (strict && unresolved.length > 0) {
        throw new PlaceholderError(
            `${describeAs}: no Markdown file found for ${unresolved.map((t) => `{{${t}}}`).join(", ")}. ` +
            `Searched for ${unresolved.map((t) => `${t}.md`).join(", ")} under ${searchRoot}.`
        );
    }

    return { content, resolved, unresolved };
}

/**
 * Substitute a file in place. The file is rewritten where it stands and nothing
 * is written beside it, which is safe because it is always a staged copy.
 */
function substituteFile(file, options) {
    const before = fs.readFileSync(file, "utf8");
    const result = substitute(before, { ...options, describeAs: file });
    if (result.content !== before) fs.writeFileSync(file, result.content);
    return result;
}

module.exports = { substitute, substituteFile, findFile, PlaceholderError, TOKEN };
