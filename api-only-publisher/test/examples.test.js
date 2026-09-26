"use strict";

const test = require("node:test");
const assert = require("node:assert");

const {
    asyncapiOperationsWithoutExamples,
    openapiOperationsWithoutExamples,
    asyncapiMessage,
    openapiMessage,
} = require("../src/examples");

// ---------------------------------------------------------------- AsyncAPI

test("a message with a non-empty examples array is not reported", () => {
    const document = {
        channels: {
            auditV1: {
                messages: {
                    registered: { examples: [{ name: "X", payload: { a: 1 } }] },
                },
            },
        },
        operations: {
            publishRegistered: {
                action: "send",
                channel: { $ref: "#/channels/auditV1" },
                messages: [{ $ref: "#/channels/auditV1/messages/registered" }],
            },
        },
    };

    assert.deepStrictEqual(asyncapiOperationsWithoutExamples(document), []);
});

test("a message with no examples, or an empty array, is reported against its operation", () => {
    const document = {
        channels: {
            auditV1: {
                messages: {
                    missing: { "x-fragment-path": "asyncapi/messages/Missing.yaml" },
                    empty: { examples: [] },
                },
            },
        },
        operations: {
            publishMissing: {
                action: "send",
                channel: { $ref: "#/channels/auditV1" },
                messages: [{ $ref: "#/channels/auditV1/messages/missing" }],
            },
            publishEmpty: {
                action: "send",
                channel: { $ref: "#/channels/auditV1" },
                messages: [{ $ref: "#/channels/auditV1/messages/empty" }],
            },
        },
    };

    const findings = asyncapiOperationsWithoutExamples(document);

    assert.deepStrictEqual(findings, [
        {
            operationId: "publishMissing", messageKey: "missing",
            fragmentPath: "asyncapi/messages/Missing.yaml", at: "/operations/publishMissing",
        },
        {
            operationId: "publishEmpty", messageKey: "empty",
            fragmentPath: null, at: "/operations/publishEmpty",
        },
    ]);
});

test("an operation naming no message explicitly is checked against every message of its channel", () => {
    const document = {
        channels: {
            auditV1: {
                messages: {
                    a: { examples: [{ name: "A", payload: {} }] },
                    b: {},
                },
            },
        },
        operations: {
            publishAll: { action: "send", channel: { $ref: "#/channels/auditV1" } },
        },
    };

    const findings = asyncapiOperationsWithoutExamples(document);

    assert.deepStrictEqual(findings.map((f) => f.messageKey), ["b"]);
});

test("the asyncapi message names the operation, the message, and says the bundle cannot be used for Microcks", () => {
    const message = asyncapiMessage({
        operationId: "publishRegistrationInitiated",
        messageKey: "registrationInitiatedMessage",
        fragmentPath: "asyncapi/messages/apionly/useraccount/RegistrationInitiatedMessageV1.yaml",
    });

    assert.strictEqual(message,
        "AsyncAPI operation 'publishRegistrationInitiated': message 'registrationInitiatedMessage' " +
        "(asyncapi/messages/apionly/useraccount/RegistrationInitiatedMessageV1.yaml) carries no example, " +
        "so this bundle cannot be used for Microcks-based conformance testing.");
});

test("with no fragment path -- the stamp sits on an ancestor, not this message itself -- the message falls back to where in the bundle", () => {
    const message = asyncapiMessage({
        operationId: "publishRegistrationInitiated", messageKey: "m", fragmentPath: null,
        at: "/operations/publishRegistrationInitiated",
    });

    assert.strictEqual(message,
        "AsyncAPI operation 'publishRegistrationInitiated': message 'm' " +
        "(at /operations/publishRegistrationInitiated) carries no example, " +
        "so this bundle cannot be used for Microcks-based conformance testing.");
});

// ----------------------------------------------------------------- OpenAPI

test("a media-type example, examples, or the referenced schema's own examples all satisfy the rule", () => {
    const document = {
        paths: {
            "/a": {
                post: {
                    operationId: "opA",
                    requestBody: { content: { "application/json": { example: { x: 1 } } } },
                    responses: { 200: { content: { "application/json": { examples: { ok: { value: {} } } } } } },
                },
            },
            "/b": {
                post: {
                    operationId: "opB",
                    requestBody: {
                        content: {
                            "application/json": {
                                schema: { $ref: "#/components/schemas/B" },
                            },
                        },
                    },
                    responses: {},
                },
            },
        },
        components: { schemas: { B: { examples: [{ x: 1 }] } } },
    };

    assert.deepStrictEqual(openapiOperationsWithoutExamples(document), []);
});

test("a request body or response with no example is reported, naming the operation and which part", () => {
    const document = {
        paths: {
            "/users": {
                post: {
                    operationId: "createUser",
                    requestBody: {
                        "x-fragment-path": "openapi/paths/Users.yaml",
                        content: { "application/json": { schema: { type: "object" } } },
                    },
                    responses: {
                        201: { content: { "application/json": { schema: { type: "object" } } } },
                        404: { description: "not found" },
                    },
                },
            },
        },
    };

    const findings = openapiOperationsWithoutExamples(document);

    assert.deepStrictEqual(findings, [
        { operationId: "createUser", part: "request body", fragmentPath: "openapi/paths/Users.yaml", at: "/paths/~1users/post" },
        { operationId: "createUser", part: "response 201", fragmentPath: null, at: "/paths/~1users/post" },
    ]);
});

test("a schema-level example is a top-level examples on the schema, not on one of its properties", () => {
    const document = {
        paths: {
            "/a": {
                get: {
                    operationId: "opA",
                    responses: {
                        200: {
                            content: {
                                "application/json": {
                                    schema: { type: "object", properties: { name: { type: "string", examples: ["alice"] } } },
                                },
                            },
                        },
                    },
                },
            },
        },
    };

    const findings = openapiOperationsWithoutExamples(document);

    assert.deepStrictEqual(findings.map((f) => f.part), ["response 200"]);
});

test("a body or response with no content, such as a 204, has nothing to exemplify and is not reported", () => {
    const document = {
        paths: {
            "/a": {
                delete: {
                    operationId: "opA",
                    responses: { 204: { description: "no content" } },
                },
            },
        },
    };

    assert.deepStrictEqual(openapiOperationsWithoutExamples(document), []);
});

test("an operation with no operationId is named by its method and path instead", () => {
    const document = {
        paths: {
            "/a/{id}": {
                get: { responses: { 200: { content: { "application/json": { schema: { type: "object" } } } } } },
            },
        },
    };

    const findings = openapiOperationsWithoutExamples(document);

    assert.strictEqual(findings[0].operationId, "GET /a/{id}");
});

test("the openapi message names the operation and the part, and never says the bundle cannot be used", () => {
    const message = openapiMessage({
        operationId: "createUser", part: "request body", fragmentPath: "openapi/paths/Users.yaml",
    });

    assert.strictEqual(message,
        "OpenAPI operation 'createUser': request body (openapi/paths/Users.yaml) carries no example, " +
        "which makes the generated documentation harder to read.");
    assert.doesNotMatch(message, /cannot be used/);
});

test("with no fragment path -- a response nested in a larger stamped fragment, say -- the message falls back to where in the bundle", () => {
    const message = openapiMessage({
        operationId: "createUser", part: "response 200", fragmentPath: null, at: "/paths/~1users/post",
    });

    assert.strictEqual(message,
        "OpenAPI operation 'createUser': response 200 (at /paths/~1users/post) carries no example, " +
        "which makes the generated documentation harder to read.");
});
