"use strict";

// A target's version is the version of its document, not of the files it is built from (#121).
//
// Whether a target changed is decided by the document a consumer receives: an edit that
// does not reach it needs no new version, and one that does needs one, wherever it was
// made. That is a question about what the bundler produces, so these tests build for real
// with the pinned bundler, in a real git history, as build.e2e.test.js does.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const { load } = require("../src/config");
const { guard } = require("../src/version-guard");
const { TOOLCHAIN_PIN } = require("../src/init-config");

const TIMEOUT = { timeout: 300000 };

const CONFIG = ({ fragmentPaths = true } = {}) => `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
defaults:
  openapi:
    lint: .redocly.yaml
    outputName: openapi.yaml
${fragmentPaths ? "" : "    fragmentPaths: false\n"}build:
  staging: build/staging
  dist: dist
toolchain:
  redocly: "${TOOLCHAIN_PIN.openapi}"
targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
`;

// alpha uses Account from a shared file that also holds Bic, which alpha does not use;
// Bic refers to a file of its own, which alpha therefore does not reach either.
const IBAN = `type: string
description: An International Bank Account Number.
pattern: '^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$'
`;

const BANKING = `Account:
  type: object
  required: [iban]
  properties:
    iban:
      $ref: '../foo/Iban.yaml'
Bic:
  $ref: './Bic.yaml'
`;

const FILES = {
    ".gitignore": "build/\ndist/\n",
    // The library's lint rules are not what these tests are about.
    ".redocly.yaml": "extends:\n  - minimal\n",
    "specs/openapi/bundles/alpha.yaml": `openapi: 3.1.1
info:
  title: Accounts
  version: 0.0.0
  description: |
    {{intro}}
paths:
  /accounts/{iban}:
    get:
      operationId: getAccount
      parameters:
        - name: iban
          in: path
          required: true
          schema:
            $ref: '../components/foo/Iban.yaml'
      responses:
        '200':
          description: The account.
          content:
            application/json:
              schema:
                $ref: '../components/common/Banking.yaml#/Account'
`,
    "specs/openapi/bundles/alpha.bundle.properties": "version=1.0.0\n",
    "specs/openapi/intro.md": "Accounts, by their IBAN.\n",
    "specs/openapi/components/foo/Iban.yaml": IBAN,
    "specs/openapi/components/common/Banking.yaml": BANKING,
    "specs/openapi/components/common/Bic.yaml": "type: string\npattern: '^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$'\n",
};

function git(dir, ...args) {
    return execFileSync("git", args, { cwd: dir, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

function write(dir, rel, content) {
    fs.mkdirSync(path.dirname(path.join(dir, rel)), { recursive: true });
    fs.writeFileSync(path.join(dir, rel), content);
}

/** The library on `main`, released as alpha 1.0.0 unless `released` is false, then on branch `topic`. */
function library({ fragmentPaths = true, released = true } = {}) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-guard-docs-"));
    write(dir, "apionly.yaml", CONFIG({ fragmentPaths }));
    for (const [rel, content] of Object.entries(FILES)) write(dir, rel, content);
    git(dir, "init", "-q", "-b", "main");
    git(dir, "config", "user.email", "test@example.invalid");
    git(dir, "config", "user.name", "Test");
    git(dir, "config", "commit.gpgsign", "false");
    git(dir, "config", "tag.gpgsign", "false");
    git(dir, "add", "-A");
    git(dir, "commit", "-qm", "initial");
    if (released) git(dir, "tag", "alpha-v1.0.0");
    git(dir, "checkout", "-qb", "topic");
    return dir;
}

function commit(dir, files, message = "change") {
    for (const [rel, content] of Object.entries(files)) write(dir, rel, content);
    git(dir, "add", "-A");
    git(dir, "commit", "-qm", message);
}

function move(dir, from, to) {
    fs.mkdirSync(path.dirname(path.join(dir, to)), { recursive: true });
    git(dir, "mv", from, to);
}

function verdict(dir) {
    return guard(load(path.join(dir, "apionly.yaml")), ["alpha"])[0];
}

const RAISED = { "specs/openapi/bundles/alpha.bundle.properties": "version=1.1.0\n" };

// ------------------------------------------------------------------ edits that do not reach the document

test("a comment-only edit leaves the document as it was, so the released version still stands", TIMEOUT, () => {
    const dir = library();
    commit(dir, { "specs/openapi/components/foo/Iban.yaml": `# Kept in line with ISO 13616.\n${IBAN}` });
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "ok", result.reason);
});

test("reformatting a fragment leaves the document as it was", TIMEOUT, () => {
    const dir = library();
    commit(dir, {
        "specs/openapi/components/foo/Iban.yaml":
            "type: \"string\"\ndescription: \"An International Bank Account Number.\"\npattern: \"^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$\"\n",
        "specs/openapi/components/common/Banking.yaml": BANKING.replace("required: [iban]", "required:\n    - iban"),
    });
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "ok", result.reason);
});

test("an edit to a part of a shared file the target does not use is no change to it", TIMEOUT, () => {
    const dir = library();
    commit(dir, {
        "specs/openapi/components/common/Banking.yaml": `${BANKING}Currency:\n  type: string\n  pattern: '^[A-Z]{3}$'\n`,
    });
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "ok", result.reason);
});

test("an edit to a file that only an unused part of a shared file refers to is no change to it", TIMEOUT, () => {
    const dir = library();
    commit(dir, { "specs/openapi/components/common/Bic.yaml": "type: string\nminLength: 8\nmaxLength: 11\n" });
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "ok", result.reason);
});

test("with fragmentPaths off, moving a fragment to another directory under the same name is no change", TIMEOUT, () => {
    const dir = library({ fragmentPaths: false });
    move(dir, "specs/openapi/components/foo/Iban.yaml", "specs/openapi/components/bar/Iban.yaml");
    commit(dir, {
        "specs/openapi/bundles/alpha.yaml": FILES["specs/openapi/bundles/alpha.yaml"].replace("foo/Iban", "bar/Iban"),
        "specs/openapi/components/common/Banking.yaml": BANKING.replace("foo/Iban", "bar/Iban"),
    });
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "ok", result.reason);
});

test("without a release tag, the merge-base's document decides as a tag's does", TIMEOUT, () => {
    const dir = library({ released: false });
    commit(dir, { "specs/openapi/components/foo/Iban.yaml": `# Kept in line with ISO 13616.\n${IBAN}` });
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "ok", result.reason);
});

// ------------------------------------------------------------------ edits that do reach the document

test("a changed description is a change, so the released version may not be published again", TIMEOUT, () => {
    const dir = library();
    commit(dir, { "specs/openapi/components/foo/Iban.yaml": IBAN.replace("An International", "The International") });
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "violation");
    assert.deepStrictEqual([result.hint.bump, result.hint.version], ["patch", "1.0.1"], "documentation only");
});

test("a changed placeholder snippet is a change, though no $ref reaches it", TIMEOUT, () => {
    const dir = library();
    commit(dir, { "specs/openapi/intro.md": "Accounts, by their IBAN, and nothing else.\n" });
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "violation");
    assert.strictEqual(result.hint.bump, "patch");
});

test("with fragmentPaths on, moving a fragment changes its x-fragment-path, so it is a change", TIMEOUT, () => {
    const dir = library();
    move(dir, "specs/openapi/components/foo/Iban.yaml", "specs/openapi/components/bar/Iban.yaml");
    commit(dir, {
        "specs/openapi/bundles/alpha.yaml": FILES["specs/openapi/bundles/alpha.yaml"].replace("foo/Iban", "bar/Iban"),
        "specs/openapi/components/common/Banking.yaml": BANKING.replace("foo/Iban", "bar/Iban"),
    });
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "violation");
    assert.strictEqual(result.hint.bump, "patch", "a move under the same name keeps the generated class's name");
});

test("without a release tag, a changed document with an unchanged version is still refused", TIMEOUT, () => {
    const dir = library({ released: false });
    commit(dir, { "specs/openapi/intro.md": "Accounts, by their IBAN, and nothing else.\n" });
    assert.strictEqual(verdict(dir).verdict, "violation");
});

// ------------------------------------------------------------------ a raised version

test("a new constraint hints at a major bump", TIMEOUT, () => {
    const dir = library();
    commit(dir, { "specs/openapi/components/foo/Iban.yaml": `${IBAN}maxLength: 34\n` });
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "violation");
    assert.deepStrictEqual([result.hint.bump, result.hint.version], ["major", "2.0.0"]);
    assert.match(result.reason, /hint: 2\.0\.0, a major bump/);
});

test("a raised version over a changed document passes", TIMEOUT, () => {
    const dir = library();
    commit(dir, { ...RAISED, "specs/openapi/intro.md": "Accounts, by their IBAN, and nothing else.\n" });
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "ok", result.reason);
});

test("a raised version over an unchanged document is a warning: it announces a change there is not", TIMEOUT, () => {
    const dir = library();
    commit(dir, { ...RAISED, "specs/openapi/components/foo/Iban.yaml": `# Kept in line with ISO 13616.\n${IBAN}` });
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "warning", result.reason);
    assert.strictEqual(result.version, "1.1.0");
    assert.match(result.reason, /1\.1\.0/);
    assert.match(result.reason, /alpha-v1\.0\.0/, "the reason names what the document was compared with");
});

test("without a release tag, a raised version over an unchanged document is a warning too", TIMEOUT, () => {
    const dir = library({ released: false });
    commit(dir, RAISED);
    const result = verdict(dir);
    assert.strictEqual(result.verdict, "warning", result.reason);
    assert.match(result.reason, /merge-base with main/);
});
