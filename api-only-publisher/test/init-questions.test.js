"use strict";

const test = require("node:test");
const assert = require("node:assert");

const { resolveValues, checkTarget, checkVersion, checkUrl, checkHost, parseKinds } = require("../src/init-questions");
const { DEFAULTS } = require("../src/init");
const { ConfigError } = require("../src/config");

/** A prompt that answers from a script, and records what it was asked. */
function scripted(...answers) {
    const asked = [];
    const told = [];
    return {
        asked, told,
        ask: async (question) => { asked.push(question); return answers.length > 0 ? answers.shift() : ""; },
        tell: (message) => told.push(message),
    };
}

test("a target name is lowercase kebab-case", () => {
    for (const ok of ["orders", "customer-orders", "orders-v2", "a1"]) assert.strictEqual(checkTarget(ok), null, ok);
    for (const bad of ["", "Orders", "customer_orders", "customer.orders", "-orders", "orders-", "customer--orders",
        "2orders", "orders v2"]) {
        assert.match(checkTarget(bad), /lowercase letters and digits, in words joined by single hyphens/, bad);
    }
});

test("a version is a semantic release version, and a URL an http or https one", () => {
    assert.strictEqual(checkVersion("0.1.0"), null);
    assert.match(checkVersion("1.0"), /not a semantic version/);
    assert.match(checkVersion("1.0.0-rc.1"), /a release version, not a pre-release/);
    assert.strictEqual(checkUrl("https://example.invalid"), null);
    assert.match(checkUrl("example.invalid"), /not an http or https URL/);
    assert.match(checkUrl("ftp://example.invalid"), /not an http or https URL/);
    assert.strictEqual(checkHost("kafka:9092"), null);
    assert.match(checkHost("kafka 9092"), /a host, optionally with a port/);
});

test("the kinds are openapi, asyncapi or both", () => {
    assert.deepStrictEqual(parseKinds("openapi"), ["openapi"]);
    assert.deepStrictEqual(parseKinds(" AsyncAPI "), ["asyncapi"]);
    assert.deepStrictEqual(parseKinds("both"), ["openapi", "asyncapi"]);
    assert.strictEqual(parseKinds("graphql"), null);
});

test("with nobody to ask, every value is its default", async () => {
    assert.deepStrictEqual(await resolveValues({}), DEFAULTS);
});

test("at the prompt, Enter takes each default, and each question shows it", async () => {
    const prompt = scripted();
    assert.deepStrictEqual(await resolveValues({ ask: prompt.ask, tell: prompt.tell }), DEFAULTS);
    assert.deepStrictEqual(prompt.asked, [
        "Kinds of document: openapi, asyncapi or both [openapi]: ",
        "Target name [example-service]: ",
        "API title [Example API]: ",
        "Initial contract version [0.1.0]: ",
        "Contact name [Example Team]: ",
        "Contact URL [https://example.invalid]: ",
        "Licence, as an SPDX identifier [Apache-2.0]: ",
        "Production server URL [https://api.example.invalid]: ",
    ]);
});

test("an invalid answer is refused, and the question asked again", async () => {
    const prompt = scripted("", "Customer_Orders", "customer-orders", "", "1.0", "1.0.0");
    const values = await resolveValues({ ask: prompt.ask, tell: prompt.tell });
    assert.strictEqual(values.target, "customer-orders");
    assert.strictEqual(values.contractVersion, "1.0.0");
    assert.strictEqual(prompt.asked.filter((q) => q.startsWith("Target name")).length, 2);
    assert.strictEqual(prompt.told.length, 2);
    assert.match(prompt.told[0], /^'Customer_Orders' is not a target name:/);
    assert.match(prompt.told[1], /^'1\.0' is not a semantic version/);
});

test("the AsyncAPI questions are asked only for a library with AsyncAPI, and the server URL only for one with OpenAPI", async () => {
    const asyncOnly = scripted("asyncapi");
    const values = await resolveValues({ ask: asyncOnly.ask, tell: asyncOnly.tell });
    assert.deepStrictEqual(values.kinds, ["asyncapi"]);
    assert.ok(asyncOnly.asked.includes("Broker host, with its port [kafka:9092]: "));
    assert.ok(!asyncOnly.asked.some((q) => q.startsWith("Production server URL")));

    const both = scripted("both");
    await resolveValues({ ask: both.ask, tell: both.tell });
    assert.ok(both.asked.some((q) => q.startsWith("Production server URL")));
    assert.ok(both.asked.some((q) => q.startsWith("Broker host")));
});

test("a known licence brings its URL; an unknown one is asked for it", async () => {
    const known = await resolveValues({ given: { license: "MIT" } });
    assert.strictEqual(known.licenseUrl, "https://opensource.org/license/mit");

    // Enter is not an answer here: an unknown licence has no URL to default to.
    const prompt = scripted("", "", "", "", "", "", "LicenseRef-Acme", "", "not a url", "https://acme.example/licence");
    const unknown = await resolveValues({ ask: prompt.ask, tell: prompt.tell });
    assert.strictEqual(unknown.license, "LicenseRef-Acme");
    assert.strictEqual(unknown.licenseUrl, "https://acme.example/licence");
    assert.deepStrictEqual(prompt.asked.filter((q) => q.startsWith("Licence URL")), ["Licence URL: ", "Licence URL: ", "Licence URL: "]);
    assert.match(prompt.told[0], /^LicenseRef-Acme is not a licence this tool knows the URL of/);

    await assert.rejects(resolveValues({ given: { license: "LicenseRef-Acme" } }),
        /--license LicenseRef-Acme has no known URL: give one with --license-url/);
});

test("a value given as a flag is not asked, and always wins", async () => {
    const prompt = scripted();
    const values = await resolveValues({
        given: { target: "orders", title: "Orders API", kinds: ["openapi", "asyncapi"] },
        ask: prompt.ask, tell: prompt.tell,
    });
    assert.strictEqual(values.target, "orders");
    assert.strictEqual(values.title, "Orders API");
    assert.deepStrictEqual(values.kinds, ["openapi", "asyncapi"]);
    assert.ok(!prompt.asked.some((q) => q.startsWith("Target name") || q.startsWith("API title") || q.startsWith("Kinds")));
});

test("an invalid flag, or one that does not apply to the kinds chosen, is refused", async () => {
    await assert.rejects(resolveValues({ given: { target: "Orders" } }), (error) =>
        error instanceof ConfigError && /^--target 'Orders' is not a target name:/.test(error.message));
    await assert.rejects(resolveValues({ given: { contractVersion: "1" } }), /--contract-version '1' is not a semantic version/);
    await assert.rejects(resolveValues({ given: { kinds: ["asyncapi"], serverUrl: "https://api.example.com" } }),
        /--server-url applies only to a library with OpenAPI/);
    await assert.rejects(resolveValues({ given: { kinds: ["openapi"], brokerHost: "kafka:9092" } }),
        /--broker-host applies only to a library with AsyncAPI/);
});
