"use strict";

// The `config` command's one section so far: `portfolio`.
//
// `init` only ever adds this section when it is missing (init-config.js);
// reconfiguring what is already there is this file's job instead, and it works
// the opposite way on purpose -- it always asks, offering what is already
// configured as each default, and always rewrites the keys it asked about.
//
// A second configurable section extends this the same way a second kind
// extended init-config.js's slots: its own question list, its own current()
// reader and its own writer, called from the same `config` command. Nothing here
// is written as a registry ahead of that need -- there is exactly one section
// today, and one small module reads more easily than an abstraction for a
// second case that does not exist yet.

const YAML = require("yaml");
const { ConfigError } = require("./config");

const PATH_STRATEGIES = ["target-prefix", "none"];

/** The portfolio section's questions, in the order they are asked. */
const QUESTIONS = [
    {
        key: "paths", flag: "--portfolio-paths",
        text: "Path prefix strategy for a portfolio: target-prefix or none",
        check: (v) => (PATH_STRATEGIES.includes(v) ? null : `use ${PATH_STRATEGIES.join(" or ")}`),
    },
    {
        key: "location", flag: "--portfolio-location",
        text: "Where a portfolio's generated bundle root lands, as a directory beside bundles/",
        check: (v) => (v.trim() ? null : "it may not be empty"),
    },
];

/**
 * Resolves the portfolio section's values: a flag always wins. At a terminal,
 * each question shows `current`'s value for it in brackets -- the section's own,
 * if it has one, or the documented default when it does not -- and Enter keeps
 * it; without a terminal, that same value is taken as given, exactly as if it
 * had been typed.
 *
 * @param {object} options
 * @param {object} [options.given] values given as flags, by key; never asked
 * @param {object} options.current the value each question defaults to
 * @param {function(string): Promise<string>} [options.ask] shows a question, resolves to the answer
 * @param {function(string): void} [options.tell] shows why an answer was refused
 * @returns {Promise<object>} the resolved values, one per question
 * @throws {ConfigError} when a flag's value is invalid
 */
async function resolvePortfolioValues({ given = {}, current, ask = null, tell = () => {} } = {}) {
    const values = {};
    for (const question of QUESTIONS) {
        const flagged = given[question.key];
        if (flagged !== undefined) {
            const problem = question.check(flagged);
            if (problem) throw new ConfigError(`${question.flag} ${problem}`);
            values[question.key] = flagged;
            continue;
        }
        const fallback = current[question.key];
        if (!ask) {
            values[question.key] = fallback;
            continue;
        }
        const prompt = `${question.text} [${fallback}]: `;
        for (;;) {
            const text = (await ask(prompt)).trim();
            if (!text) {
                values[question.key] = fallback;
                break;
            }
            const problem = question.check(text);
            if (!problem) {
                values[question.key] = text;
                break;
            }
            tell(problem);
        }
    }
    return values;
}

/**
 * Writes `values` into apionly.yaml's portfolio section, wholesale for the keys
 * asked about and untouched everywhere else: a hand-set portfolio.openapi.tags,
 * for one, is not something `config portfolio` asked about, so it survives.
 * YAML.parseDocument, never a parse and restringify -- the file's comments, and
 * every value this did not ask about, are not this command's to change.
 *
 * @returns {string} the edited text
 */
function writePortfolioSection(text, values) {
    const doc = YAML.parseDocument(text);
    doc.setIn(["portfolio", "openapi", "paths"], values.paths);
    doc.setIn(["portfolio", "location"], values.location);
    return doc.toString();
}

module.exports = { resolvePortfolioValues, writePortfolioSection, QUESTIONS };
