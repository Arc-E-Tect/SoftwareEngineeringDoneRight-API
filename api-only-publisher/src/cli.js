#!/usr/bin/env node
"use strict";

const fs = require("fs");
const path = require("path");
const YAML = require("yaml");

const { loadFrom, ConfigError } = require("./config");
const { build, prepare, BuildError } = require("./pipeline");
const { init, scaffold, plan } = require("./init");
const { resolveValues } = require("./init-questions");
const { resolvePortfolioValues, writePortfolioSection } = require("./config-command");
const { PlaceholderError } = require("./placeholders");
const { VersionError } = require("./version");
const { forTargets, ClosureError } = require("./closure");
const { pack, PackError } = require("./pack");
const { changedSince, ChangedError } = require("./changed");
const { split, SplitError } = require("./split");
const { publish, ChannelError } = require("./channels");
const { VersionError: PolicyError, describe } = require("./version-policy");
const { versionOf, BundleVersionError } = require("./bundle-version");
const { unreferenced } = require("./unreferenced");

const USAGE = `api-only-publisher -- build and distribute API description documents

Usage:
  api-only-publisher init [dir] [--yes] [--force] [--openapi] [--asyncapi] [--target <name>]
                          [--title <text>] [--contract-version <version>]
                          [--contact-name <text>] [--contact-url <url>]
                          [--license <spdx-id>] [--license-url <url>]
                          [--server-url <url>] [--broker-host <host[:port]>]
  api-only-publisher config [section] [--yes] [--portfolio-paths <target-prefix|none>]
                            [--portfolio-location <dir>]
  api-only-publisher build [--target <name>]... [--pre-release <ids>] [--openapi|--asyncapi]
  api-only-publisher lint  [--target <name>]...
  api-only-publisher targets
  api-only-publisher closure [--target <name>]...
  api-only-publisher changed --since <ref>
  api-only-publisher pack [--pre-release <ids>] [--target <name>]... [--out <dir>]
  api-only-publisher publish [--pre-release <ids>] [--target <name>]... [--channel <name>]... [--out <dir>]
  api-only-publisher split --out <dir> [--by kind|target]

Options:
  --target <name>   Restrict to one target; repeat for several. Default: all.
  --pre-release <ids>
                    build/pack/publish: append pre-release identifiers, such as
                    rc.1, to each target's version.
  --openapi         Only build OpenAPI documents.
  --asyncapi        Only build AsyncAPI documents.
  --force           init only: overwrite files that differ from the scaffold;
                    at a terminal, after listing them and asking once.
  -y, --yes         init/config only: ask nothing, even at a terminal; take the
                    current or default value for anything no flag gives.
  --openapi, --asyncapi
                    init: the kinds of document the library holds; both flags for both.
  --target <name>   init: the target's name, in lowercase kebab-case.
  --title, --contract-version, --contact-name, --contact-url, --license,
  --license-url, --server-url, --broker-host
                    init only: the value of that question; a flag always wins.
  --portfolio-paths <target-prefix|none>, --portfolio-location <dir>
                    config only: the value of that question; a flag always wins.
  --since <ref>     changed only: the git ref to compare the working tree against.
  --out <dir>       pack/publish/split: where to write.
  --channel <name>  publish only: repeat for several. Default: every configured channel.
  --by <kind|target>  split only: the boundary to split along. Default: kind.
  -C <dir>          Run as if started in <dir>.
  -q, --quiet       Only report errors.
  -h, --help        Show this help.

What gets built, and where each document goes, is declared in apionly.yaml.
A published target's version is read from its version file:
<target>.bundle.properties beside its bundle root, or the file its versionFile names.
`;

function parseArgs(argv) {
    const options = {
        targets: [], kinds: null, preRelease: null, quiet: false, force: false,
        dir: process.cwd(), since: null, out: null, channels: null, by: "kind",
        yes: false, init: {}, config: {},
    };
    const INIT_VALUES = {
        "--title": "title", "--contract-version": "contractVersion", "--contact-name": "contactName",
        "--contact-url": "contactUrl", "--license": "license", "--license-url": "licenseUrl",
        "--server-url": "serverUrl", "--broker-host": "brokerHost",
    };
    const CONFIG_VALUES = { "--portfolio-paths": "paths", "--portfolio-location": "location" };
    const positional = [];

    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i];
        const next = () => {
            if (i + 1 >= argv.length) throw new ConfigError(`${arg} requires a value`);
            return argv[++i];
        };
        switch (arg) {
            case "--target": options.targets.push(next()); break;
            case "--pre-release": options.preRelease = next(); break;
            case "--version":
                throw new ConfigError(
                    "--version is no longer accepted: each published target's version is read from its version " +
                    "file, <target>.bundle.properties beside its bundle root. Pass --pre-release <ids> to cut a " +
                    "pre-release of it."
                );
            case "--openapi": options.kinds = (options.kinds || []).concat("openapi"); break;
            case "--asyncapi": options.kinds = (options.kinds || []).concat("asyncapi"); break;
            case "--force": options.force = true; break;
            case "--since": options.since = next(); break;
            case "--out": options.out = next(); break;
            case "--channel": options.channels = (options.channels || []).concat(next()); break;
            case "--by": options.by = next(); break;
            case "-C": options.dir = path.resolve(next()); break;
            case "-q": case "--quiet": options.quiet = true; break;
            case "-h": case "--help": options.help = true; break;
            case "-y": case "--yes": options.yes = true; break;
            default:
                if (INIT_VALUES[arg]) {
                    options.init[INIT_VALUES[arg]] = next();
                    break;
                }
                if (CONFIG_VALUES[arg]) {
                    options.config[CONFIG_VALUES[arg]] = next();
                    break;
                }
                if (arg.startsWith("-")) throw new ConfigError(`unrecognized option '${arg}'`);
                positional.push(arg);
        }
    }
    return { options, positional };
}

// The targets pack and publish write an archive for: those selected, less any
// that are never published. Only these need a closure, so only these need to have
// been staged -- building one target must not depend on every other being staged.
function shippedTargets(config, targets) {
    return Object.keys(config.targets)
        .filter((target) => (!targets || targets.includes(target)) && config.isPublished(target));
}

// The version of each target in `names`, keyed by target. Every one is resolved
// before any target is acted on, so a missing or malformed version file stops the
// command before it has built or shipped anything.
function versionsOf(config, names, preRelease) {
    return new Map(names.map((target) => [target, versionOf(config, target, { preRelease })]));
}

/** The kinds --openapi and --asyncapi name, in the order the scaffold writes them. */
function initKinds(kinds) {
    return kinds ? ["openapi", "asyncapi"].filter((kind) => kinds.includes(kind)) : undefined;
}

/**
 * Whether init should ask about the portfolio section this run: "ask", once the
 * library is about to have more than one target -- there is nothing to aggregate
 * with just one -- and it does not have a portfolio section yet; "present" the
 * same way, but the section is already there, which init never changes, and
 * reports rather than silently doing nothing about; "not-yet" while there is
 * still only one target, which is not worth mentioning at all.
 *
 * @returns {"ask"|"present"|"not-yet"}
 */
function portfolioStatus(dir, target) {
    const file = path.join(dir, "apionly.yaml");
    if (!fs.existsSync(file)) return "not-yet";
    const doc = YAML.parseDocument(fs.readFileSync(file, "utf8"));
    if (doc.errors.length > 0) return "not-yet";
    const targets = doc.get("targets", true);
    const names = YAML.isMap(targets) ? new Set(targets.items.map((pair) => String(pair.key))) : new Set();
    names.add(target);
    if (names.size <= 1) return "not-yet";
    return doc.hasIn(["portfolio"]) ? "present" : "ask";
}

/**
 * Scaffolds a library. At a terminal, and without --yes, it asks for every value no
 * flag gives; anywhere else it takes the defaults, as it always has.
 *
 * @param {object} io where a terminal is: `interactive`, and the `input` and `output`
 *     streams to ask on; process.stdin and process.stdout unless the caller says otherwise
 */
async function runInit(options, positional, io, log) {
    if (options.targets.length > 1) {
        throw new ConfigError(`init scaffolds one target; --target was given ${options.targets.length} times`);
    }
    const dir = path.resolve(options.dir, positional[1] || ".");
    const input = io.input || process.stdin;
    const output = io.output || process.stdout;
    const interactive = io.interactive !== undefined ? io.interactive : Boolean(input.isTTY && output.isTTY);
    const given = { ...options.init, target: options.targets[0], kinds: initKinds(options.kinds) };
    for (const key of Object.keys(given)) if (given[key] === undefined) delete given[key];

    const terminal = interactive && !options.yes
        ? require("node:readline/promises").createInterface({ input, output })
        : null;
    try {
        const values = await resolveValues({
            given,
            ask: terminal && ((question) => terminal.question(question)),
            tell: (message) => output.write(`${message}\n`),
        });

        // Asked, or defaulted, the same way a kind's own questions are -- but only
        // once there is something to aggregate. A section already there is never
        // asked about or changed, and reported as present rather than passed over
        // in silence.
        const portfolioStatusThisRun = portfolioStatus(dir, values.target);
        let portfolio = null;
        if (portfolioStatusThisRun === "ask") {
            portfolio = await resolvePortfolioValues({
                given: options.config,
                current: { paths: "target-prefix", location: "portfolios" },
                ask: terminal && ((question) => terminal.question(question)),
                tell: (message) => output.write(`${message}\n`),
            });
        }

        log(`Scaffolding a specification library in ${dir}`);
        let force = options.force;
        if (terminal && force) {
            // Asked, --force means "after showing me": it overwrites what differs only
            // once the list has been seen and agreed to.
            const differing = plan(dir, scaffold(values), values, portfolio).filter((entry) => entry.status === "differs");
            if (differing.length > 0) {
                output.write(`These files differ from the scaffold:\n${differing.map((d) => `  ${d.rel}\n`).join("")}`);
                const answer = await terminal.question(`Overwrite these ${differing.length} file(s)? [y/N] `);
                force = /^y(es)?$/i.test(answer.trim());
            }
        }
        init(dir, { values, force, log, portfolio });
        if (portfolioStatusThisRun === "present") log("  present    portfolio (already configured; init never changes it)");
        log(`\nNext: api-only-publisher build -C ${dir}`);
    } finally {
        if (terminal) terminal.close();
    }
    return 0;
}

const CONFIGURABLE_SECTIONS = ["portfolio"];

/**
 * Reconfigures a section of apionly.yaml. Unlike init, this always asks -- at a
 * terminal, each question shows what is already configured, or the documented
 * default when there is nothing yet, and Enter keeps it -- and always rewrites
 * the keys it asked about, whatever else the section or the file holds.
 */
async function runConfig(options, positional, io, log) {
    const section = positional[1];
    if (section !== undefined && !CONFIGURABLE_SECTIONS.includes(section)) {
        throw new ConfigError(
            `'${section}' is not a configurable section; there is: ${CONFIGURABLE_SECTIONS.join(", ")}`
        );
    }
    const config = loadFrom(options.dir);
    const input = io.input || process.stdin;
    const output = io.output || process.stdout;
    const interactive = io.interactive !== undefined ? io.interactive : Boolean(input.isTTY && output.isTTY);
    const terminal = interactive && !options.yes
        ? require("node:readline/promises").createInterface({ input, output })
        : null;
    try {
        for (const name of section ? [section] : CONFIGURABLE_SECTIONS) {
            // The only configurable section today; a second one gets its own current
            // values, its own given-flags and its own writer, called the same way.
            const current = { paths: config.portfolioPathStrategy(), location: config.portfolioLocation() };
            const given = { ...options.config };
            for (const key of Object.keys(given)) if (given[key] === undefined) delete given[key];

            const values = await resolvePortfolioValues({
                given, current,
                ask: terminal && ((question) => terminal.question(question)),
                tell: (message) => output.write(`${message}\n`),
            });
            fs.writeFileSync(config.path, writePortfolioSection(fs.readFileSync(config.path, "utf8"), values));
            log(`Configured ${name}: paths=${values.paths}, location=${values.location}`);
        }
    } finally {
        if (terminal) terminal.close();
    }
    return 0;
}

async function main(argv, io = {}) {
    const { options, positional } = parseArgs(argv);
    const command = positional[0];

    if (options.help || !command) {
        process.stdout.write(USAGE);
        return 0;
    }

    const log = options.quiet ? () => {} : (message) => console.log(message);

    if (command === "init") return runInit(options, positional, io, log);
    if (command === "config") return runConfig(options, positional, io, log);

    const config = loadFrom(options.dir);
    const targets = options.targets.length > 0 ? options.targets : null;

    if (targets) {
        for (const t of targets) {
            if (!config.targets[t]) {
                throw new ConfigError(
                    `unknown target '${t}'; declared targets are ${Object.keys(config.targets).join(", ")}`
                );
            }
        }
    }

    switch (command) {
        case "targets": {
            for (const name of Object.keys(config.targets)) {
                const kinds = ["openapi", "asyncapi"].filter((k) => config.targets[name][k]);
                const published = config.isPublished(name) ? "" : "  (publish: false)";
                console.log(`${name}  [${kinds.join(", ")}]${published}`);
            }
            return 0;
        }
        case "build": {
            // A target is stamped only if it is published: a documentation view has no
            // version of its own, and needs no version file.
            const kinds = options.kinds || ["openapi", "asyncapi"];
            const stamped = Object.keys(config.targets).filter((target) =>
                (!targets || targets.includes(target)) && config.isPublished(target) &&
                kinds.some((kind) => config.targets[target][kind]));
            const versions = versionsOf(config, stamped, options.preRelease);
            const results = build(config, {
                targets, kinds, log, versionOf: (target) => versions.get(target) || null,
            });
            log(`\nBuilt ${results.length} document(s).`);
            return 0;
        }
        case "lint": {
            // Lint without rebuilding, for fast local feedback on what is already
            // in dist/. Every selected document is linted even when one fails, so
            // one run reports every failure rather than only the first.
            const { lint } = require("./pipeline");
            const failures = [];
            let linted = 0;
            for (const kind of ["openapi", "asyncapi"]) {
                for (const target of config.targetsFor(kind)) {
                    if (targets && !targets.includes(target)) continue;
                    const file = path.join(config.distDir(target), config.outputName(kind));
                    if (!fs.existsSync(file)) {
                        failures.push(`${target} (${kind}): ${file} does not exist; run 'build' first`);
                        continue;
                    }
                    log(`=== ${target} (${kind}) ===`);
                    try {
                        lint(config, kind, file, log, {
                            report: true,
                            reportFile: config.lintReport(target, kind),
                        });
                    } catch (error) {
                        if (!(error instanceof BuildError)) throw error;
                        console.error(error.message);
                        failures.push(`${target} (${kind})`);
                    }
                    linted += 1;
                }
            }
            log(`\nLinted ${linted} document(s).`);

            // A fragment no target reaches is never linted, so it is looked for
            // here -- only when every target is linted, since it belongs to none.
            const mode = config.lintUnreferenced();
            let orphaned = null;
            if (!targets && mode !== "off") {
                prepare(config);
                const orphans = unreferenced(config);
                const reportFile = config.unreferencedReport();
                fs.mkdirSync(path.dirname(reportFile), { recursive: true });
                fs.writeFileSync(reportFile, orphans.map((file) => `${file}\n`).join(""));
                if (orphans.length > 0) {
                    orphaned =
                        `${orphans.length} fragment(s) not reachable from any target, so nothing lints them:\n` +
                        orphans.map((file) => `  ${file}`).join("\n") +
                        "\nReference each one from a target's bundle, or delete it.";
                    if (mode === "warn") log(orphaned);
                }
            }

            const problems = [];
            if (failures.length > 0) {
                problems.push(`lint failed for ${failures.length} document(s): ${failures.join("; ")}`);
            }
            if (orphaned && mode === "error") problems.push(orphaned);
            if (problems.length > 0) throw new BuildError(problems.join("\n\n"));
            return 0;
        }
        case "closure": {
            // Prepared first: the closure is computed over substituted content,
            // because a change to a Markdown snippet genuinely changes the
            // published document.
            prepare(config);
            for (const [target, entry] of forTargets(config)) {
                if (targets && !targets.includes(target)) continue;
                console.log(`${target}  ${entry.files.length} file(s)  ${entry.sha256}`);
            }
            return 0;
        }
        case "changed": {
            if (!options.since) throw new ConfigError("changed requires --since <ref>");
            prepare(config);
            const results = changedSince(config, options.since, { log });
            const changed = results.filter((r) => r.changed);
            if (options.quiet) for (const r of changed) console.log(r.target);
            log(`\n${changed.length} of ${results.length} target(s) changed since ${options.since}.`);
            return 0;
        }
        case "pack": {
            const outDir = path.resolve(options.dir, options.out || "build/packages");
            const shipped = shippedTargets(config, targets);
            const versions = versionsOf(config, shipped, options.preRelease);
            const closures = forTargets(config, undefined, shipped);
            let packed = 0;
            for (const target of Object.keys(config.targets)) {
                if (targets && !targets.includes(target)) continue;
                if (!config.isPublished(target)) {
                    log(`${target}: not packed (publish: false)`);
                    continue;
                }
                const closure = closures.get(target);
                pack(config, target, {
                    version: versions.get(target),
                    closureSha256: closure ? closure.sha256 : null,
                    outDir, log,
                });
                packed += 1;
            }
            log(`\nPacked ${packed} target(s) into ${outDir}.`);
            return 0;
        }
        case "publish": {
            const outDir = path.resolve(options.dir, options.out || "build/packages");
            const configured = config.channels || {};
            const names = options.channels || Object.keys(configured);
            if (names.length === 0) {
                throw new ConfigError("no channels configured; add a `channels:` block or pass --channel");
            }
            const shipped = shippedTargets(config, targets);
            const versions = versionsOf(config, shipped, options.preRelease);
            const closures = forTargets(config, undefined, shipped);
            let published = 0;
            for (const target of Object.keys(config.targets)) {
                if (targets && !targets.includes(target)) continue;
                if (!config.isPublished(target)) continue;
                const closure = closures.get(target);
                // Packed once, then shipped unchanged to every channel.
                const { archive, manifest } = pack(config, target, {
                    version: versions.get(target),
                    closureSha256: closure ? closure.sha256 : null,
                    outDir, log,
                });
                for (const name of names) {
                    // A remote channel returns a promise; a local one does not.
                    await publish(archive, manifest, name, { ...(configured[name] || {}), baseDir: config.root }, log);
                    published += 1;
                }
            }
            log(`\nPublished ${published} artifact(s).`);
            return 0;
        }
        case "split": {
            const outDir = path.resolve(options.dir, options.out || "build/split");
            prepare(config);
            const parts = split(config, { by: options.by, outDir, log });
            const duplicated = parts.reduce((n, p) => n + p.imported.length, 0);
            log(`\nWrote ${parts.length} part(s) to ${outDir}.`);
            if (duplicated > 0) {
                log(`${duplicated} shared fragment(s) were copied so each part is self-contained.`);
                log("Each part carrying copies has an IMPORTED.adoc saying which, and what it costs.");
            }
            return 0;
        }
        default:
            throw new ConfigError(`unrecognized command '${command}'`);
    }
}

function report(error) {
    if (error instanceof ConfigError || error instanceof BuildError ||
        error instanceof PlaceholderError || error instanceof VersionError ||
        error instanceof ClosureError || error instanceof PackError ||
        error instanceof ChangedError || error instanceof SplitError ||
        error instanceof ChannelError || error instanceof PolicyError ||
        error instanceof BundleVersionError) {
        console.error(`Error: ${error.message}`);
        process.exitCode = 1;
    } else {
        throw error;
    }
}

if (require.main === module) {
    main(process.argv.slice(2)).then(
        (code) => { process.exitCode = code; },
        report
    );
}

module.exports = { main, parseArgs };
