"use strict";

// Tests for the parts of config.js prompt 8 adds: unknown-key rejection, the
// `portfolio` section and its defaults, and the validation rules an aggregate
// target must satisfy. test/config.test.js covers everything config.js already did.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { load, ConfigError } = require("../src/config");

const BASE = `schemaVersion: 1
sources:
  root: specs
  openapi: openapi
  asyncapi: asyncapi
defaults:
  openapi:
    lint: .redocly.yaml
    outputName: openapi.yaml
  asyncapi:
    outputName: asyncapi.yaml
build:
  staging: build/staging
  dist: dist
toolchain:
  redocly: "@redocly/cli@2.52.0"
  asyncapi: "@asyncapi/cli@6.0.2"
targets:
  alpha:
    openapi:
      bundle: bundles/alpha.yaml
    asyncapi:
      bundle: bundles/alpha.yaml
  beta:
    openapi:
      bundle: bundles/beta.yaml
    asyncapi:
      bundle: bundles/beta.yaml
`;

function write(contents) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aop-config-portfolio-"));
    const file = path.join(dir, "apionly.yaml");
    fs.writeFileSync(file, contents);
    return file;
}

function refuses(contents, pattern) {
    assert.throws(() => load(write(contents)), (e) => e instanceof ConfigError && pattern.test(e.message));
}

// ---- unknown-key rejection ------------------------------------------------

test("an unknown key at the root is refused, naming the key and the file", () => {
    refuses(BASE + "nonsense: true\n", /unknown key 'nonsense' in apionly\.yaml/);
});

test("a mis-indented section is caught where it lands, not where it was meant to go", () => {
    // portfolio: meant as a top-level section, landed under build: by a bad indent.
    const bad = BASE.replace("build:\n  staging: build/staging\n  dist: dist\n",
        "build:\n  staging: build/staging\n  dist: dist\n  portfolio:\n    security: push-down\n");
    refuses(bad, /unknown key 'portfolio' in build/);
});

test("an unknown key in sources, defaults, defaults.openapi, defaults.asyncapi, build, reports, lint and distribution is refused", () => {
    refuses(BASE.replace("sources:\n  root: specs", "sources:\n  root: specs\n  nope: x"), /unknown key 'nope' in sources/);
    refuses(BASE.replace("defaults:\n  openapi:", "defaults:\n  nope: x\n  openapi:"), /unknown key 'nope' in defaults/);
    refuses(BASE.replace("    outputName: openapi.yaml\n  asyncapi:", "    outputName: openapi.yaml\n    nope: x\n  asyncapi:"),
        /unknown key 'nope' in defaults\.openapi/);
    refuses(BASE.replace("  asyncapi:\n    outputName: asyncapi.yaml\n", "  asyncapi:\n    outputName: asyncapi.yaml\n    nope: x\n"),
        /unknown key 'nope' in defaults\.asyncapi/);
    refuses(BASE.replace("build:\n  staging: build/staging\n  dist: dist\n", "build:\n  staging: build/staging\n  dist: dist\n  nope: x\n"),
        /unknown key 'nope' in build/);
    refuses(BASE + "reports:\n  nope: x\n", /unknown key 'nope' in reports/);
    refuses(BASE + "lint:\n  nope: x\n", /unknown key 'nope' in lint/);
    refuses(BASE + "distribution:\n  root: .\n  nope: x\n", /unknown key 'nope' in distribution/);
});

test("an unknown key on a target, or inside one of its kinds, is refused", () => {
    refuses(BASE + "  gamma:\n    nope: x\n    openapi:\n      bundle: bundles/gamma.yaml\n",
        /unknown key 'nope' in targets\.gamma/);
    refuses(BASE.replace("  alpha:\n    openapi:\n      bundle: bundles/alpha.yaml\n",
        "  alpha:\n    openapi:\n      bundle: bundles/alpha.yaml\n      nope: x\n"),
        /unknown key 'nope' in targets\.alpha\.openapi/);
});

test("defaults.placeholders keeps its own key list", () => {
    refuses(BASE.replace("  asyncapi:\n    outputName: asyncapi.yaml\n",
        "  asyncapi:\n    outputName: asyncapi.yaml\n  placeholders:\n    nope: x\n"),
        /unknown key 'nope' in defaults\.placeholders/);
});

test("channels and toolchain are open-ended, by design, and are not checked against a fixed key list", () => {
    // Channel and tool names are chosen by the author, not from a fixed vocabulary.
    const config = load(write(BASE + "channels:\n  file:\n    directory: build/publish\n    somethingChannelSpecific: 1\n"));
    assert.deepStrictEqual(config.channels.file.somethingChannelSpecific, 1);
});

// ---- portfolio section: defaults, shape, unknown keys --------------------

test("an absent portfolio section resolves to documented defaults", () => {
    const config = load(write(BASE));
    assert.strictEqual(config.portfolioPathStrategy(), "target-prefix");
    assert.strictEqual(config.portfolioOperationIdStrategy(), "target-prefix");
    assert.strictEqual(config.portfolioTagStrategy(), "reconcile");
    assert.strictEqual(config.portfolioSecurityStrategy(), "push-down");
    assert.strictEqual(config.portfolioLocation(), "portfolios");
});

test("a portfolio section overrides only the values it sets", () => {
    const config = load(write(BASE + "portfolio:\n  openapi:\n    paths: none\n  location: aggregates\n"));
    assert.strictEqual(config.portfolioPathStrategy(), "none");
    assert.strictEqual(config.portfolioLocation(), "aggregates");
    // Untouched settings keep their defaults.
    assert.strictEqual(config.portfolioOperationIdStrategy(), "target-prefix");
    assert.strictEqual(config.portfolioTagStrategy(), "reconcile");
    assert.strictEqual(config.portfolioSecurityStrategy(), "push-down");
});

test("portfolio.openapi.paths accepts only target-prefix or none", () => {
    refuses(BASE + "portfolio:\n  openapi:\n    paths: whatever\n",
        /portfolio\.openapi\.paths must be target-prefix or none, not "whatever"/);
});

test("an unknown key in portfolio, or in portfolio.openapi, is refused", () => {
    refuses(BASE + "portfolio:\n  nope: x\n", /unknown key 'nope' in portfolio/);
    refuses(BASE + "portfolio:\n  openapi:\n    nope: x\n", /unknown key 'nope' in portfolio\.openapi/);
});

// ---- aggregate validation --------------------------------------------------

function withPortfolio(extra = "") {
    return BASE + `  portfolio:
    publish: false
    openapi:
      aggregate:
        - alpha
        - beta
${extra}`;
}

test("an aggregate target needs no bundle, and declaring one alongside aggregate is refused", () => {
    const config = load(write(withPortfolio()));
    assert.strictEqual(config.targets.portfolio.openapi.bundle, undefined);

    refuses(withPortfolio().replace("      aggregate:\n        - alpha\n        - beta\n",
        "      bundle: bundles/portfolio.yaml\n      aggregate:\n        - alpha\n        - beta\n"),
        /target 'portfolio' declares both aggregate and bundle for openapi/);
});

test("declaring aggregate for a target implies it is not published, whether or not publish: false is also written", () => {
    const withExplicitFalse = load(write(withPortfolio()));
    assert.strictEqual(withExplicitFalse.isPublished("portfolio"), false);

    const withoutPublishAtAll = load(write(withPortfolio().replace("    publish: false\n", "")));
    assert.strictEqual(withoutPublishAtAll.isPublished("portfolio"), false);
});

test("publish: true on a target with an aggregate is refused, and says what to do instead", () => {
    refuses(withPortfolio().replace("    publish: false\n", "    publish: true\n"),
        /target 'portfolio' declares an aggregate; it is never published.*author one instead/s);
});

test("a target mixing an aggregate for one kind with a hand-written bundle for another kind is refused", () => {
    refuses(withPortfolio() + "    asyncapi:\n      bundle: bundles/portfolio.yaml\n",
        /target 'portfolio' aggregates openapi but has a hand-written bundle for asyncapi/);
});

test("an aggregate declaring no members is refused at load time, by name", () => {
    refuses(withPortfolio().replace("      aggregate:\n        - alpha\n        - beta\n", "      aggregate: []\n"),
        /targets\.portfolio\.openapi\.aggregate must list at least one target/);
});

test("an aggregate listing itself is refused", () => {
    refuses(withPortfolio().replace("        - alpha\n        - beta\n", "        - portfolio\n"),
        /target 'portfolio' aggregates itself/);
});

test("an aggregate listing another aggregate is refused -- nesting is not supported", () => {
    const nested = withPortfolio() + `  another:
    publish: false
    openapi:
      aggregate:
        - portfolio
`;
    refuses(nested, /target 'another' aggregates 'portfolio', which is itself an aggregate; nesting is not supported/);
});
