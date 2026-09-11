"use strict";

// Placeholder substitution.
//
// Absorbed from sedr_utils/openapi/prep_openapi's preprocess_openapi.js, with
// three defects fixed rather than inherited:
//
//   1. Search scope. The original searched for <placeholder>.md recursively
//      downward from the *input file's own directory*, never from the -d
//      argument the caller passed. The workaround was to move the Markdown
//      files next to whatever referenced them, and the gotcha had to be
//      documented. The search root is now a parameter, defaulting to the source
//      root the caller actually named.
//   2. Failure mode. A missing Markdown file produced a warning and the literal
//      string *MISSING CONTENT* in the output, so a broken document shipped from
//      a green build. Unresolved placeholders are now an error by default.
//   3. Token grammar. The pattern \{\{(\w+)\}\} silently excluded '-' and '.'
//      from placeholder names, so {{status-codes}} was left in the output rather
//      than reported. The grammar now admits them.

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
 * Substitute a file in place. Unlike the tool this replaces, nothing named
 * merged_* is left behind: the file is rewritten where it stands, which is safe
 * because it is always a staged copy.
 */
function substituteFile(file, options) {
    const before = fs.readFileSync(file, "utf8");
    const result = substitute(before, { ...options, describeAs: file });
    if (result.content !== before) fs.writeFileSync(file, result.content);
    return result;
}

module.exports = { substitute, substituteFile, findFile, PlaceholderError, TOKEN };
