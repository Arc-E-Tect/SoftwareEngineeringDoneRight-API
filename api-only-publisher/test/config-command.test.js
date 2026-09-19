"use strict";

const test = require("node:test");
const assert = require("node:assert");
const YAML = require("yaml");

const { resolvePortfolioValues, writePortfolioSection, QUESTIONS } = require("../src/config-command");
const { ConfigError } = require("../src/config");

function scripted(...answers) {
    const asked = [];
    const told = [];
    return {
        asked, told,
        ask: async (question) => { asked.push(question); return answers.length > 0 ? answers.shift() : ""; },
        tell: (message) => told.push(message),
    };
}

const CURRENT = { paths: "target-prefix", location: "portfolios" };

test("with nobody to ask, every value is the current one", async () => {
    assert.deepStrictEqual(await resolvePortfolioValues({ current: CURRENT }), CURRENT);
});

test("at the prompt, each question shows the current value, and Enter keeps it", async () => {
    const prompt = scripted();
    const values = await resolvePortfolioValues({ current: CURRENT, ask: prompt.ask, tell: prompt.tell });
    assert.deepStrictEqual(values, CURRENT);
    assert.ok(prompt.asked.some((q) => q.includes("[target-prefix]")));
    assert.ok(prompt.asked.some((q) => q.includes("[portfolios]")));
});

test("a changed answer at the prompt is taken, and an invalid one is refused and asked again", async () => {
    const prompt = scripted("whatever", "none", "aggregates");
    const values = await resolvePortfolioValues({ current: CURRENT, ask: prompt.ask, tell: prompt.tell });
    assert.strictEqual(values.paths, "none");
    assert.strictEqual(values.location, "aggregates");
    assert.strictEqual(prompt.told.length, 1);
    assert.match(prompt.told[0], /target-prefix or none/);
});

test("a value given as a flag is not asked, and always wins", async () => {
    const prompt = scripted();
    const values = await resolvePortfolioValues({
        given: { paths: "none" }, current: CURRENT, ask: prompt.ask, tell: prompt.tell,
    });
    assert.strictEqual(values.paths, "none");
    assert.strictEqual(values.location, "portfolios");
    assert.ok(!prompt.asked.some((q) => q.startsWith("Path prefix")));
});

test("an invalid flag is refused by name, and asks nothing", async () => {
    await assert.rejects(resolvePortfolioValues({ given: { paths: "sideways" }, current: CURRENT }),
        (e) => e instanceof ConfigError && /--portfolio-paths use target-prefix or none/.test(e.message));
    await assert.rejects(resolvePortfolioValues({ given: { location: "  " }, current: CURRENT }),
        (e) => e instanceof ConfigError && /--portfolio-location it may not be empty/.test(e.message));
});

test("every question has a flag and a name, for the CLI and the error messages", () => {
    for (const q of QUESTIONS) {
        assert.ok(q.flag.startsWith("--portfolio-"));
        assert.ok(q.key);
        assert.ok(q.text);
    }
});

test("writePortfolioSection adds a fresh section to a file that has none, keeping its comments", () => {
    const before = `# apionly.yaml
#
# A library's own comment, which must survive.

schemaVersion: 1
sources:
  root: specs
targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
`;
    const after = writePortfolioSection(before, { paths: "none", location: "aggregates" });
    const doc = YAML.parse(after);
    assert.deepStrictEqual(doc.portfolio, { openapi: { paths: "none" }, location: "aggregates" });
    assert.match(after, /# A library's own comment, which must survive\./);
});

test("writePortfolioSection overwrites only the keys it asked about, leaving the rest of the section alone", () => {
    const before = `schemaVersion: 1
sources:
  root: specs
targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
portfolio:
  openapi:
    paths: target-prefix
    operationIds: target-prefix
    tags: reconcile
  security: push-down
  location: portfolios
`;
    const after = writePortfolioSection(before, { paths: "none", location: "aggregates" });
    const doc = YAML.parse(after);
    assert.strictEqual(doc.portfolio.openapi.paths, "none");
    assert.strictEqual(doc.portfolio.location, "aggregates");
    // Untouched, because config portfolio only asked about paths and location.
    assert.strictEqual(doc.portfolio.openapi.operationIds, "target-prefix");
    assert.strictEqual(doc.portfolio.openapi.tags, "reconcile");
    assert.strictEqual(doc.portfolio.security, "push-down");
});
