"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { stampFiles, componentPaths, strayPaths, fragmentStamps, unresolvedStamps, FragmentPathError, KEY } = require("../src/fragment-paths");

function tree(files) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-fragment-paths-"));
    for (const [rel, content] of Object.entries(files)) {
        const file = path.join(dir, rel);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, content);
    }
    return dir;
}

test("every YAML mapping is stamped with its path from the root, as its first key", () => {
    const root = tree({
        "openapi/components/common/User.yaml": "type: object\n",
        "openapi/paths/Users.yml": "get:\n  summary: x\n",
    });

    const stamped = stampFiles(root);

    assert.deepStrictEqual(stamped, ["openapi/components/common/User.yaml", "openapi/paths/Users.yml"]);
    assert.strictEqual(
        fs.readFileSync(path.join(root, "openapi/components/common/User.yaml"), "utf8"),
        `${KEY}: openapi/components/common/User.yaml\ntype: object\n`);
});

test("a file whose root is not a mapping, or that is not YAML, is left alone", () => {
    const root = tree({
        "openapi/shared/servers.yaml": "- url: https://example.invalid\n",
        "openapi/shared/intro.md": "prose\n",
        "openapi/shared/empty.yaml": "",
    });

    assert.deepStrictEqual(stampFiles(root), []);
    assert.strictEqual(fs.readFileSync(path.join(root, "openapi/shared/servers.yaml"), "utf8"),
        "- url: https://example.invalid\n");
});

test("only the named files are stamped when a selection is given", () => {
    const root = tree({ "a/One.yaml": "type: string\n", "a/Two.yaml": "type: string\n" });

    assert.deepStrictEqual(stampFiles(root, { only: new Set(["a/Two.yaml"]) }), ["a/Two.yaml"]);
    assert.strictEqual(fs.readFileSync(path.join(root, "a/One.yaml"), "utf8"), "type: string\n");
});

test("a fragment that writes the key itself is refused: the Publisher owns it", () => {
    const root = tree({ "a/One.yaml": `${KEY}: somewhere/else.yaml\ntype: string\n` });

    assert.throws(() => stampFiles(root), (error) =>
        error instanceof FragmentPathError && /a\/One\.yaml/.test(error.message) && error.message.includes(KEY));
});

test("component paths are read per component, and a component without one maps to null", () => {
    const document = {
        components: {
            schemas: { User: { [KEY]: "openapi/User.yaml", type: "object" }, Inline: { type: "string" } },
            responses: { Problem: { [KEY]: "openapi/Problem.yaml" } },
        },
    };

    assert.deepStrictEqual([...componentPaths(document)], [
        ["schemas/User", "openapi/User.yaml"],
        ["schemas/Inline", null],
        ["responses/Problem", "openapi/Problem.yaml"],
    ]);
    assert.deepStrictEqual([...componentPaths({ openapi: "3.1.1" })], []);
});

test("a stamp anywhere but directly on a component is stray, and is reported by where it is", () => {
    const document = {
        [KEY]: "root.yaml",
        info: { [KEY]: "info.yaml" },
        tags: [{ [KEY]: "tag.yaml" }],
        components: { schemas: { User: { [KEY]: "User.yaml", properties: { a: { [KEY]: "nested.yaml" } } } } },
    };

    assert.deepStrictEqual(strayPaths(document), [
        { at: "/", path: "root.yaml" },
        { at: "/info", path: "info.yaml" },
        { at: "/tags/0", path: "tag.yaml" },
        { at: "/components/schemas/User/properties/a", path: "nested.yaml" },
    ]);
    assert.deepStrictEqual(strayPaths({ components: { schemas: { User: { [KEY]: "User.yaml" } } } }), []);
});

test("every stamp in a document is found, wherever it sits", () => {
    const document = {
        asyncapi: "3.0.0",
        channels: {
            auditV1: {
                [KEY]: "asyncapi/channels/Audit.yaml",
                address: "iff.audit.v1",
                messages: {
                    registered: {
                        [KEY]: "asyncapi/messages/Registered.yaml",
                        payload: {
                            [KEY]: "asyncapi/components/schemas/RegisteredEvent.yaml",
                            properties: { username: { [KEY]: "openapi/components/schemas/UsernameV1.yaml" } },
                        },
                    },
                },
            },
        },
        operations: [{ [KEY]: "asyncapi/operations/Publish.yaml" }],
    };

    assert.deepStrictEqual(fragmentStamps(document), [
        { at: "/channels/auditV1", path: "asyncapi/channels/Audit.yaml" },
        { at: "/channels/auditV1/messages/registered", path: "asyncapi/messages/Registered.yaml" },
        { at: "/channels/auditV1/messages/registered/payload", path: "asyncapi/components/schemas/RegisteredEvent.yaml" },
        { at: "/channels/auditV1/messages/registered/payload/properties/username", path: "openapi/components/schemas/UsernameV1.yaml" },
        { at: "/operations/0", path: "asyncapi/operations/Publish.yaml" },
    ]);
});

test("a document with no stamps has none", () => {
    assert.deepStrictEqual(fragmentStamps({ asyncapi: "3.0.0", channels: {} }), []);
});

test("a stamp that names no file in the library is reported, and one that does is not", () => {
    const root = tree({ "asyncapi/messages/Registered.yaml": "name: RegisteredV1\n" });
    const document = {
        channels: {
            auditV1: {
                messages: {
                    registered: { [KEY]: "asyncapi/messages/Registered.yaml" },
                    gone: { [KEY]: "asyncapi/messages/Removed.yaml" },
                },
            },
        },
    };

    assert.deepStrictEqual(unresolvedStamps(document, root), [
        { at: "/channels/auditV1/messages/gone", path: "asyncapi/messages/Removed.yaml" },
    ]);
});

test("the file a stamp is left alone by, such as a bundle root, is never stamped", () => {
    const root = tree({ "bundles/audit.yaml": "asyncapi: 3.0.0\n", "messages/One.yaml": "name: One\n" });

    assert.deepStrictEqual(stampFiles(root, { except: new Set(["bundles/audit.yaml"]) }), ["messages/One.yaml"]);
    assert.strictEqual(fs.readFileSync(path.join(root, "bundles/audit.yaml"), "utf8"), "asyncapi: 3.0.0\n");
});
