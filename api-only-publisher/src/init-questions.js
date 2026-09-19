"use strict";

// The values `init` scaffolds from: asked for at a terminal, taken from flags, or
// defaulted -- in that order of precedence, a flag always winning.
//
// Nothing here reads input. The caller hands in `ask`, a function that shows a
// question and resolves to the answer, which is how the CLI connects a terminal
// and how the tests connect a script; without one, nobody is asked.

const { DEFAULTS } = require("./init");
const { ConfigError } = require("./config");
const { parse } = require("./version-policy");

/** SPDX identifiers whose licence text has a well-known URL, so it need not be asked for. */
const LICENSE_URLS = Object.freeze({
    "Apache-2.0": "https://www.apache.org/licenses/LICENSE-2.0.html",
    "MIT": "https://opensource.org/license/mit",
    "BSD-2-Clause": "https://opensource.org/license/bsd-2-clause",
    "BSD-3-Clause": "https://opensource.org/license/bsd-3-clause",
    "MPL-2.0": "https://www.mozilla.org/en-US/MPL/2.0/",
    "EPL-2.0": "https://www.eclipse.org/legal/epl-2.0/",
    "GPL-3.0-only": "https://www.gnu.org/licenses/gpl-3.0.html",
    "LGPL-3.0-only": "https://www.gnu.org/licenses/lgpl-3.0.html",
});

// A target name becomes a file name, a properties key, a Maven artifactId, a git tag
// and, through the Subscriber, a Gradle task name -- which treats -, _ and . alike, so
// only one of them may separate words, or two targets could claim one task.
const TARGET = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;

function checkTarget(value) {
    return TARGET.test(value) ? null
        : "use lowercase letters and digits, in words joined by single hyphens, starting with a letter, " +
          "such as customer-orders";
}

function checkVersion(value) {
    let version;
    try {
        version = parse(value);
    } catch (error) {
        return "it is not a semantic version, such as 0.1.0";
    }
    return version.prerelease ? "a contract starts at a release version, not a pre-release" : null;
}

function checkUrl(value) {
    try {
        const url = new URL(value);
        if (url.protocol === "http:" || url.protocol === "https:") return null;
    } catch (error) {
        // Not a URL at all; reported below like any other.
    }
    return "it is not an http or https URL";
}

function checkHost(value) {
    return /^[A-Za-z0-9.-]+(:\d{1,5})?$/.test(value) ? null : "give a host, optionally with a port, such as kafka:9092";
}

function checkText(value) {
    return value.trim() ? null : "it may not be empty";
}

/** The kinds a library holds, from what someone typed; null when it names none. */
function parseKinds(text) {
    switch (text.trim().toLowerCase()) {
        case "openapi": return ["openapi"];
        case "asyncapi": return ["asyncapi"];
        case "both": return ["openapi", "asyncapi"];
        default: return null;
    }
}

const hasOpenapi = (v) => v.kinds.includes("openapi");
const hasAsyncapi = (v) => v.kinds.includes("asyncapi");

/**
 * The questions, in the order they are asked. `when` says whether a question applies
 * to the values chosen so far; `flag` is the command-line option that answers it.
 */
const QUESTIONS = [
    {
        key: "kinds", flag: "--openapi/--asyncapi", text: "Kinds of document: openapi, asyncapi or both",
        show: (kinds) => (kinds.length === 2 ? "both" : kinds[0]),
        read: (text) => {
            const kinds = parseKinds(text);
            return kinds ? { value: kinds } : { problem: `'${text}' is not openapi, asyncapi or both` };
        },
    },
    { key: "target", flag: "--target", text: "Target name", check: checkTarget, name: "a target name" },
    { key: "title", flag: "--title", text: "API title", check: checkText, name: "a title" },
    { key: "contractVersion", flag: "--contract-version", text: "Initial contract version", check: checkVersion, name: "a release version" },
    { key: "contactName", flag: "--contact-name", text: "Contact name", check: checkText, name: "a contact name" },
    { key: "contactUrl", flag: "--contact-url", text: "Contact URL", check: checkUrl },
    { key: "license", flag: "--license", text: "Licence, as an SPDX identifier", check: checkText, name: "a licence" },
    {
        key: "licenseUrl", flag: "--license-url", text: "Licence URL", check: checkUrl,
        derive: (v) => LICENSE_URLS[v.license],
    },
    { key: "serverUrl", flag: "--server-url", text: "Production server URL", check: checkUrl, when: hasOpenapi },
    { key: "brokerHost", flag: "--broker-host", text: "Broker host, with its port", check: checkHost, when: hasAsyncapi, name: "a broker host" },
];

/** Reads an answer to a question: the value, or what is wrong with it. */
function read(question, text) {
    if (question.read) return question.read(text);
    const problem = question.check(text);
    if (!problem) return { value: text };
    // "'x' is not a target name: use ..." reads better than "'x': use ...", where a
    // question names what it asks for; "it is not ..." reasons stand on their own.
    return { problem: problem.startsWith("it ") ? `'${text}' ${problem.slice(3)}` : `'${text}' is not ${question.name}: ${problem}` };
}

/**
 * Works out every value `init` scaffolds from.
 *
 * @param {object} options
 * @param {object} [options.given] values given as flags, by key; they are never asked
 * @param {function(string): Promise<string>} [options.ask] shows a question, resolves to the answer
 * @param {function(string): void} [options.tell] shows why an answer was refused
 * @returns {Promise<object>} the values, as DEFAULTS has them
 * @throws {ConfigError} when a flag's value is invalid, or does not apply to the kinds chosen
 */
async function resolveValues({ given = {}, ask = null, tell = () => {} } = {}) {
    const values = {};
    for (const question of QUESTIONS) {
        const applies = !question.when || question.when({ ...DEFAULTS, ...values });
        const flagged = given[question.key];
        if (!applies) {
            if (flagged !== undefined) {
                throw new ConfigError(`${question.flag} applies only to a library with ${
                    question.key === "serverUrl" ? "OpenAPI" : "AsyncAPI"}`);
            }
            continue;
        }
        if (flagged !== undefined) {
            if (question.key === "kinds") {
                values.kinds = flagged;
                continue;
            }
            const answer = read(question, flagged);
            if (answer.problem) throw new ConfigError(`${question.flag} ${answer.problem}`);
            values[question.key] = answer.value;
            continue;
        }
        // A question that derives its value is not asked when it can be derived -- a known
        // licence brings its URL -- and has no default when it cannot: one must be given.
        const fallback = question.derive ? question.derive({ ...DEFAULTS, ...values }) : DEFAULTS[question.key];
        if (question.derive && fallback !== undefined) {
            values[question.key] = fallback;
            continue;
        }
        if (!ask) {
            if (fallback === undefined) {
                throw new ConfigError(`--license ${values.license} has no known URL: give one with --license-url`);
            }
            values[question.key] = fallback;
            continue;
        }
        const prompt = fallback === undefined ? `${question.text}: `
            : `${question.text} [${question.show ? question.show(fallback) : fallback}]: `;
        for (;;) {
            const text = (await ask(prompt)).trim();
            if (!text && fallback !== undefined) {
                values[question.key] = fallback;
                break;
            }
            if (!text) {
                tell(`${values.license} is not a licence this tool knows the URL of: give it`);
                continue;
            }
            const answer = read(question, text);
            if (!answer.problem) {
                values[question.key] = answer.value;
                break;
            }
            tell(answer.problem);
        }
    }
    return { ...DEFAULTS, ...values };
}

module.exports = {
    resolveValues, checkTarget, checkVersion, checkUrl, checkHost, parseKinds, QUESTIONS, LICENSE_URLS,
};
