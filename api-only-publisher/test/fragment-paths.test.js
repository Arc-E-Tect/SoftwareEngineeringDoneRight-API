"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { stampFiles, componentPaths, strayPaths, FragmentPathError, KEY } = require("../src/fragment-paths");

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
