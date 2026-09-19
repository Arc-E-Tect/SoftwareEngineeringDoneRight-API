"use strict";

// Generated aggregate bundles.
//
// A portfolio view used to be a hand-written OpenAPI bundle root: every path body
// already lives in a $ref'd fragment, so a whole-landscape table of contents cost
// one file that duplicated no contract text. It can be written that way, but it
// does not follow that it should be: nothing stopped it silently falling behind
// the members it was meant to list, the moment a service grew an endpoint and
// nobody remembered the table of contents was a second place to add it.
//
// AsyncAPI never had the choice. Its operations use document-root pointers --
// `channel: {$ref: '#/channels/auditV1'}` -- and `#` resolves against whatever
// file contains it, so moving an operation into a fragment breaks it. A
// hand-written async portfolio would therefore have to copy every operation
// verbatim, and that copy would start rotting the moment a member changed.
//
// So both are generated: the aggregate's bundle root is synthesised into the
// staged tree from its members, immediately before bundling. Nothing is
// duplicated in the source, and the view cannot fall behind its members.
//
// Generation happens in two passes, because a member's bundle root is read
// *before* it is bundled -- staging only copies and substitutes placeholders --
// and at that point almost everything in it is still a $ref pointer, not the
// content behind it:
//
//   Pass 1, here, merges what is visible unresolved: paths and security schemes
//   are keyed by a map key that is already there in the bundle root, and a
//   member's own root-level `security` is written inline, never $ref'd. OpenAPI
//   paths are prefixed with the member's target at this pass, because that is a
//   plain string rewrite of the key -- it needs nothing resolved.
//
//   Pass 2, in pipeline.js, runs on the aggregate's own bundled output, once
//   every $ref -- the members' and its own -- has been resolved. Only then are an
//   operation's id, and whether it already sets its own `security`, visible at
//   all, so that is where operation ids are prefixed, root security is pushed
//   down onto the operations it reaches, and repeated tags are reconciled by the
//   name inside them, which a $ref pointer to a tag fragment never shows.

const fs = require("fs");
const path = require("path");
const YAML = require("yaml");

class AggregateError extends Error {}

const HTTP_METHODS = ["get", "put", "post", "delete", "options", "head", "patch", "trace"];

/**
 * Merge one section of a member's document into the aggregate.
 *
 * Three modes, for three different expectations of what a repeated key means:
 *
 * `unique` (the default) treats a repeated key as ambiguous ownership, whatever
 * it says -- ordinary for a channel, an operation or a path, where one of two
 * identical definitions silently not appearing in the aggregate is exactly the
 * kind of mistake this is here to catch.
 *
 * `agree` marks sections where members are expected to say the same thing.
 * Several services publishing to one broker all declare that broker, and that is
 * the ordinary case rather than a conflict -- so an identical definition merges
 * silently and only a genuine disagreement is an error.
 *
 * `reconcile` is for a section where members are expected to sometimes *disagree*
 * without either being wrong -- a shared tag two members describe slightly
 * differently is an inconsistency to fix, not a defect to fail the build over.
 * An identical repeat merges silently, like `agree`; a differing one merges too,
 * with the first contributing member's value kept and the disagreement reported,
 * rather than failing the build.
 *
 * @returns {Array<{section: string, key: string, winner: string, loser: string}>}
 *     one entry per reconciled disagreement, always empty outside `reconcile` mode
 */
function mergeSection(into, from, section, member, seen, { mode = "unique", label = section } = {}) {
    const reconciled = [];
    if (!from[section]) return reconciled;
    for (const [key, value] of Object.entries(from[section])) {
        const previous = seen[section] && seen[section][key];
        if (previous) {
            const identical = JSON.stringify(into[section][key]) === JSON.stringify(value);
            if (identical) {
                if (mode === "unique") {
                    throw new AggregateError(
                        `aggregate: '${member}' redefines ${label}.${key}, already contributed by '${previous}'. ` +
                        "Rename it, or leave it out of the aggregate."
                    );
                }
                continue;
            }
            if (mode === "reconcile") {
                reconciled.push({ section, key, winner: previous, loser: member });
                continue;
            }
            throw new AggregateError(
                `aggregate: '${member}' ${mode === "agree" ? "disagrees about" : "redefines"} ` +
                `${label}.${key}, already contributed by '${previous}'. ` +
                (mode === "agree"
                    ? "Members may share a server, but not define it differently."
                    : "Rename it, or leave it out of the aggregate.")
            );
        }
        into[section] = into[section] || {};
        into[section][key] = value;
        seen[section] = seen[section] || {};
        seen[section][key] = member;
    }
    return reconciled;
}

/** Merges every sub-map of `components` present in `from`, `mode` for each. */
function mergeComponents(into, from, member, seen, mode) {
    if (!from.components) return;
    into.components = into.components || {};
    seen.components = seen.components || {};
    for (const sub of Object.keys(from.components)) {
        into.components[sub] = into.components[sub] || {};
        seen.components[sub] = seen.components[sub] || {};
        mergeSection(into.components, from.components, sub, member, seen.components, { mode, label: `components.${sub}` });
    }
}

function requireMember(config, target, kind, member) {
    if (!config.targets[member] || !config.targets[member][kind]) {
        throw new AggregateError(`target '${target}': aggregate member '${member}' declares no ${kind} bundle`);
    }
    if (config.targets[member][kind].aggregate !== undefined) {
        throw new AggregateError(
            `target '${target}': aggregate member '${member}' is itself an aggregate; nesting is not supported`
        );
    }
}

function memberDocument(config, target, kind, member) {
    requireMember(config, target, kind, member);
    const file = path.join(config.stagingDir(kind), config.targets[member][kind].bundle);
    if (!fs.existsSync(file)) {
        throw new AggregateError(`aggregate member '${member}': bundle root not found at ${file}`);
    }
    return YAML.parse(fs.readFileSync(file, "utf8"));
}

function membersOf(config, target, kind) {
    const members = config.targets[target][kind].aggregate;
    if (!Array.isArray(members) || members.length === 0) {
        throw new AggregateError(`target '${target}': ${kind}.aggregate must list at least one target`);
    }
    return members;
}

/** The aggregate's own identity: its own info, or a generated stand-in naming its members. */
function aggregateInfo(spec, target, members, forKind) {
    return spec.info || {
        title: `${target} (aggregate)`,
        version: "0.0.0",
        description: forKind === "asyncapi"
            ? `Every event contract published across ${members.join(", ")}.`
            : `Every API published across ${members.join(", ")}.`,
    };
}

function writeGenerated(out, members, content) {
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(
        out,
        `# GENERATED by api-only-publisher from: ${members.join(", ")}\n` +
        "# Do not edit, and do not commit: it is rebuilt into the staging tree on every build.\n" +
        YAML.stringify(content)
    );
}

/**
 * Synthesise an aggregate AsyncAPI bundle root into the staged tree.
 *
 * @returns {string} the path of the generated bundle root
 */
function generateAsyncApi(config, target, { log = () => {} } = {}) {
    const spec = config.targets[target].asyncapi;
    const members = membersOf(config, target, "asyncapi");

    const merged = { asyncapi: null, info: null, servers: {}, channels: {}, operations: {} };
    const seen = {};

    for (const member of members) {
        const doc = memberDocument(config, target, "asyncapi", member);

        merged.asyncapi = merged.asyncapi || doc.asyncapi;
        mergeSection(merged, doc, "servers", member, seen, { mode: "agree" });
        mergeSection(merged, doc, "channels", member, seen);
        mergeSection(merged, doc, "operations", member, seen);
    }

    merged.info = aggregateInfo(spec, target, members, "asyncapi");

    const out = config.aggregatePath(target, "asyncapi");
    writeGenerated(out, members, merged);
    log(`-- Generated aggregate ${path.basename(out)} from ${members.join(", ")}`);
    return out;
}

/**
 * Synthesise an aggregate OpenAPI bundle root into the staged tree.
 *
 * Only what a member's bundle root holds unresolved: paths (prefixed, so keys are
 * unique by construction -- see openapiPushDown for what happens to the
 * operations behind them once they are resolved), servers, root-level security,
 * and every `components.*` sub-map. Tags are carried over as whatever the member
 * declared -- usually a list of $ref pointers -- and reconciled by name in
 * openapiPushDown, once bundling has resolved them into `{name, ...}` objects a
 * $ref pointer never shows.
 *
 * @returns {string} the path of the generated bundle root
 */
function generateOpenApi(config, target, { log = () => {} } = {}) {
    const spec = config.targets[target].openapi;
    const members = membersOf(config, target, "openapi");
    const prefixPaths = config.portfolioPathStrategy() === "target-prefix";

    const merged = { openapi: null, info: null, servers: {}, tags: [], paths: {}, components: {} };
    const seen = {};
    // Recorded here, in pass 1, because only here is it still known which member a
    // path came from: once paths are merged, one document can no longer tell.
    const owners = {};

    for (const member of members) {
        const doc = memberDocument(config, target, "openapi", member);

        merged.openapi = merged.openapi || doc.openapi;
        mergeSection(merged, doc, "servers", member, seen, { mode: "agree" });
        mergeComponents(merged, doc, member, seen, "agree");
        if (Array.isArray(doc.tags)) merged.tags.push(...doc.tags);

        const prefixed = {};
        for (const [rawPath, item] of Object.entries(doc.paths || {})) {
            prefixed[prefixPaths ? `/${member}${rawPath}` : rawPath] = item;
        }
        mergeSection(merged, { paths: prefixed }, "paths", member, seen);
        for (const finalPath of Object.keys(prefixed)) {
            owners[finalPath] = { member, security: doc.security };
        }
    }
    if (Object.keys(merged.components).length === 0) delete merged.components;
    if (merged.tags.length === 0) delete merged.tags;

    merged.info = aggregateInfo(spec, target, members, "openapi");

    const out = config.aggregatePath(target, "openapi");
    writeGenerated(out, members, merged);
    log(`-- Generated aggregate ${path.basename(out)} from ${members.join(", ")}`);
    return { out, owners };
}

const SECURITY_NOTE =
    "Each member's own root-level `security` has been moved onto the operations it contributes " +
    "-- see each operation's own `security`, and `components.securitySchemes` for what each scheme requires.";

/**
 * Pass 2: what generateOpenApi could not do until its own output was bundled.
 * Run on `text` -- the aggregate's *bundled* document -- after every $ref, the
 * members' and its own, has been resolved.
 *
 * - Every operation id is prefixed with its path's member, the same way the path
 *   itself already was, so two members sharing an operation id -- coincidence,
 *   not intent -- cannot collide in the portfolio.
 * - Every member's root-level `security` -- explicit `[]` included -- becomes
 *   every operation it contributes to's own `security`, unless the operation
 *   already set one; the portfolio itself keeps no root `security`. See the
 *   Security section of the README for why: union and intersection are both
 *   unsound merges of a security requirement, and requiring every member to
 *   agree would make a landscape where they legitimately differ unbuildable.
 *   Where anything was pushed down, `info.description` and `x-security-note`
 *   both say so -- a comment would not survive bundling, so neither carries this.
 * - Two members contributing the same tag name are one tag; an identical body
 *   merges silently, a differing one is reported and the first contributor's
 *   body wins, deterministically.
 *
 * Edits the document in place -- `YAML.parseDocument`, never a parse and
 * re-stringify -- so everything this pass does not touch keeps the bundler's own
 * formatting exactly, the same reason version.js splices rather than re-emits.
 *
 * @returns {{text: string, reconciled: Array<{section: string, key: string}>}}
 */
function openapiPushDown(text, owners, { operationIdStrategy = "target-prefix" } = {}) {
    const doc = YAML.parseDocument(text);
    const plain = doc.toJS();
    let pushedDown = false;

    for (const [pathKey, item] of Object.entries(plain.paths || {})) {
        const owner = owners[pathKey];
        if (!owner || !item || typeof item !== "object") continue;
        for (const httpMethod of HTTP_METHODS) {
            const op = item[httpMethod];
            if (!op || typeof op !== "object") continue;
            if (op.operationId && operationIdStrategy === "target-prefix") {
                doc.setIn(["paths", pathKey, httpMethod, "operationId"], `${pascalCase(owner.member)}${op.operationId}`);
            }
            if (op.security === undefined && owner.security !== undefined) {
                doc.setIn(["paths", pathKey, httpMethod, "security"], owner.security);
                pushedDown = true;
            }
        }
    }

    if (pushedDown) {
        const description = doc.getIn(["info", "description"]);
        doc.setIn(["info", "description"], description ? `${description}\n\n${SECURITY_NOTE}` : SECURITY_NOTE);
        doc.setIn(["x-security-note"], SECURITY_NOTE);
    }

    const reconciled = [];
    if (Array.isArray(plain.tags)) {
        const byName = new Map();
        const order = [];
        for (const tag of plain.tags) {
            const existing = byName.get(tag.name);
            if (existing === undefined) {
                byName.set(tag.name, tag);
                order.push(tag.name);
                continue;
            }
            if (JSON.stringify(existing) !== JSON.stringify(tag)) {
                reconciled.push({ section: "tags", key: tag.name });
            }
            // The first contributor's body wins either way -- identical or not.
        }
        doc.setIn(["tags"], order.map((name) => byName.get(name)));
    }

    return { text: doc.toString(), reconciled };
}

/** A target name, in PascalCase: user-account -> UserAccount. */
function pascalCase(target) {
    return target.split(/[-_]/).map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join("");
}

function isAggregate(config, target, kind) {
    const spec = config.targets[target][kind];
    return Boolean(spec && spec.aggregate);
}

module.exports = {
    generateAsyncApi, generateOpenApi, openapiPushDown, isAggregate, mergeSection, pascalCase, AggregateError,
};
