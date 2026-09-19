"use strict";

// Whether a bundled document's operations carry examples the rest of the API-Only
// toolchain can do something with.
//
// Both OpenAPI and AsyncAPI make examples optional, and the specification linters
// are right to accept their absence: a contract without one is a valid contract.
// This is a toolchain-compatibility check instead -- can Microcks, or the
// TranscriberJ, do anything useful with what is about to be published -- and the
// answer, and so the message, differs per kind. Neither runs from a Redocly plugin
// or a Spectral ruleset: one implementation, one message shape, aware of why it
// cares, reported apart from either linter's own findings.
//
// AsyncAPI: Microcks builds its async mocks and its conformance tests from a
// message's own `examples` -- an array of {name, summary, payload} on the Message
// Object itself. A message with none cannot be used for Microcks-based conformance
// testing at all, so the message says exactly that.
//
// OpenAPI: nothing downstream currently reads an example. The TranscriberJ
// processes a bundle without one perfectly well -- bodies take arguments,
// constraints become constants. So the OpenAPI message claims only what is true:
// weaker documentation, never that the bundle cannot be used.

const KEY = "x-fragment-path";

/** The value at a local JSON pointer (`#/a/b/0`) within `document`, or undefined. */
function pointer(document, ref) {
    if (typeof ref !== "string" || !ref.startsWith("#/")) return undefined;
    let node = document;
    for (const raw of ref.slice(2).split("/")) {
        if (node === null || typeof node !== "object") return undefined;
        const segment = raw.replace(/~1/g, "/").replace(/~0/g, "~");
        node = node[segment];
    }
    return node;
}

/** `node`, or what it points to when it is a `{ $ref }`. */
function resolve(document, node) {
    if (node && typeof node === "object" && typeof node.$ref === "string") {
        return pointer(document, node.$ref);
    }
    return node;
}

/**
 * Every AsyncAPI operation whose message carries no example, in operation order.
 *
 * An operation naming no message explicitly acts on every message of its channel,
 * so each of those is checked in its place. A message this document cannot resolve
 * -- a dangling `$ref`, which lint would already have refused -- is skipped rather
 * than reported here.
 *
 * @returns {Array<{operationId: string, messageKey: string, fragmentPath: string|null, at: string}>}
 */
function asyncapiOperationsWithoutExamples(document) {
    const findings = [];
    const operations = document.operations || {};
    for (const [operationId, operation] of Object.entries(operations)) {
        if (!operation || typeof operation !== "object") continue;
        const channel = resolve(document, operation.channel);
        const named = Array.isArray(operation.messages) ? operation.messages : [];
        const entries = named.length > 0
            ? named.map((ref) => [
                typeof ref.$ref === "string" ? ref.$ref.split("/").pop() : null,
                resolve(document, ref),
            ])
            : Object.entries((channel && channel.messages) || {});

        for (const [messageKey, message] of entries) {
            if (!message || typeof message !== "object" || messageKey === null) continue;
            const examples = message.examples;
            if (Array.isArray(examples) && examples.length > 0) continue;
            findings.push({
                operationId,
                messageKey,
                fragmentPath: message[KEY] || null,
                at: `/operations/${operationId}`,
            });
        }
    }
    return findings;
}

/** Whether any media type of a request body or response object has an example. */
function hasExample(document, bodyOrResponse) {
    const content = bodyOrResponse.content;
    if (!content || typeof content !== "object") return true; // nothing to exemplify
    return Object.values(content).some((mediaType) => {
        if (!mediaType || typeof mediaType !== "object") return false;
        if (mediaType.example !== undefined) return true;
        if (mediaType.examples && Object.keys(mediaType.examples).length > 0) return true;
        const schema = resolve(document, mediaType.schema);
        return !!(schema && Array.isArray(schema.examples) && schema.examples.length > 0);
    });
}

const METHODS = ["get", "put", "post", "delete", "options", "head", "patch", "trace"];

/**
 * Every OpenAPI operation whose request body or a response carries no example, in
 * path-and-method order.
 *
 * An entity counts as having one when a media type declares `example` or
 * `examples` directly, or when the schema that media type's `schema` resolves to
 * -- not a property inside it -- carries its own top-level `examples`. A body or
 * response with no `content` at all, such as a 204, has nothing to exemplify and
 * is not reported.
 *
 * @returns {Array<{operationId: string, part: string, fragmentPath: string|null, at: string}>}
 */
function openapiOperationsWithoutExamples(document) {
    const findings = [];
    const paths = document.paths || {};
    for (const [route, pathItem] of Object.entries(paths)) {
        if (!pathItem || typeof pathItem !== "object") continue;
        for (const method of METHODS) {
            const operation = pathItem[method];
            if (!operation || typeof operation !== "object") continue;
            const operationId = operation.operationId || `${method.toUpperCase()} ${route}`;
            const at = `/paths/${route.replace(/~/g, "~0").replace(/\//g, "~1")}/${method}`;

            if (operation.requestBody) {
                const body = resolve(document, operation.requestBody);
                if (body && !hasExample(document, body)) {
                    findings.push({ operationId, part: "request body", fragmentPath: body[KEY] || null, at });
                }
            }

            for (const [status, raw] of Object.entries(operation.responses || {})) {
                const response = resolve(document, raw);
                if (response && !hasExample(document, response)) {
                    findings.push({
                        operationId, part: `response ${status}`, fragmentPath: response[KEY] || null, at,
                    });
                }
            }
        }
    }
    return findings;
}

/**
 * The warning text for one AsyncAPI finding.
 *
 * @param {{operationId: string, messageKey: string, fragmentPath: string|null}} finding
 * @returns {string}
 */
function asyncapiMessage(finding) {
    const where = finding.fragmentPath || `at ${finding.at}`;
    return `AsyncAPI operation '${finding.operationId}': message '${finding.messageKey}' (${where}) ` +
        "carries no example, so this bundle cannot be used for Microcks-based conformance testing.";
}

/**
 * The warning text for one OpenAPI finding.
 *
 * @param {{operationId: string, part: string, fragmentPath: string|null}} finding
 * @returns {string}
 */
function openapiMessage(finding) {
    const where = finding.fragmentPath || `at ${finding.at}`;
    return `OpenAPI operation '${finding.operationId}': ${finding.part} (${where}) ` +
        "carries no example, which makes the generated documentation harder to read.";
}

module.exports = {
    asyncapiOperationsWithoutExamples,
    openapiOperationsWithoutExamples,
    asyncapiMessage,
    openapiMessage,
};
