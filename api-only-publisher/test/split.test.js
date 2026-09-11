"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { load } = require("../src/config");
const { split, SplitError } = require("../src/split");

// A miniature library with the same shape as the real one: an AsyncAPI schema
// that reaches into the OpenAPI tree, which is what makes a naive repository
// split break.
const FILES = {
    "apionly.yaml": `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
  asyncapi: asyncapi
defaults:
  openapi:
    outputName: openapi.yaml
  asyncapi:
    outputName: asyncapi.yaml
build:
  staging: build/staging
targets:
  svc:
    openapi:
      bundle: bundles/svc.yaml
    asyncapi:
      bundle: svc.yaml
`,
    "specs/openapi/bundles/svc.yaml": "paths:\n  /a:\n    $ref: '../paths/A.yaml'\n",
    "specs/openapi/paths/A.yaml": "get:\n  x:\n    $ref: '../components/common/Username.yaml'\n",
    "specs/openapi/components/common/Username.yaml": "type: string\n",
    "specs/asyncapi/svc.yaml": "channels:\n  c:\n    $ref: 'messages/M.yaml'\n",
    "specs/asyncapi/messages/M.yaml": "payload:\n  $ref: '../../openapi/components/common/Username.yaml'\n",
};

function library() {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-split-"));
    for (const [rel, content] of Object.entries(FILES)) {
        const file = path.join(dir, rel);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, content);
    }
    const config = load(path.join(dir, "apionly.yaml"));
    // split reads the staged tree; stage by copying, as the pipeline does.
    for (const kind of ["openapi", "asyncapi"]) {
        fs.cpSync(config.sourceRoot(), config.stagingRoot(kind), { recursive: true });
    }
    return { dir, config };
}

test("each part gets a copy of every foreign file it reaches", () => {
    const { dir, config } = library();
    const out = path.join(dir, "out");
    const parts = split(config, { by: "kind", outDir: out });

    const asyncPart = parts.find((p) => p.part === "asyncapi");
    assert.deepStrictEqual(asyncPart.imported, ["openapi/components/common/Username.yaml"]);
    assert.ok(fs.existsSync(path.join(out, "asyncapi", "openapi/components/common/Username.yaml")));
});

test("copies keep their original relative path, so no $ref needs rewriting", () => {
    // This is what makes the split safe: nothing is rewritten, so no rewrite can
    // get the relative depth wrong.
    const { dir, config } = library();
    const out = path.join(dir, "out");
    split(config, { by: "kind", outDir: out });

    const message = path.join(out, "asyncapi", "asyncapi/messages/M.yaml");
    const text = fs.readFileSync(message, "utf8");
    assert.match(text, /\$ref: '\.\.\/\.\.\/openapi\/components\/common\/Username\.yaml'/);

    const referenced = path.resolve(path.dirname(message), "../../openapi/components/common/Username.yaml");
    assert.ok(fs.existsSync(referenced), "the $ref must resolve inside the part");
});

test("a part that imports nothing gets no note; one that does, does", () => {
    const { dir, config } = library();
    const out = path.join(dir, "out");
    split(config, { by: "kind", outDir: out });
    assert.ok(!fs.existsSync(path.join(out, "openapi", "IMPORTED.adoc")));

    const note = fs.readFileSync(path.join(out, "asyncapi", "IMPORTED.adoc"), "utf8");
    assert.match(note, /copies/);
    assert.match(note, /drift/);
    assert.match(note, /openapi\/components\/common\/Username\.yaml/);
});

test("splitting by target gives each target a self-contained tree", () => {
    const { dir, config } = library();
    const out = path.join(dir, "out");
    const parts = split(config, { by: "target", outDir: out });

    assert.deepStrictEqual(parts.map((p) => p.part), ["svc"]);
    // Both subtrees are the target's own, so nothing counts as imported.
    assert.deepStrictEqual(parts[0].imported, []);
    assert.ok(fs.existsSync(path.join(out, "svc", "openapi/components/common/Username.yaml")));
    assert.ok(fs.existsSync(path.join(out, "svc", "asyncapi/messages/M.yaml")));
});

test("an unknown boundary is refused", () => {
    const { dir, config } = library();
    assert.throws(() => split(config, { by: "repository", outDir: path.join(dir, "out") }),
        (e) => e instanceof SplitError && /expected 'kind' or 'target'/.test(e.message));
});
