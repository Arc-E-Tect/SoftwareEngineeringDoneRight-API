#!/usr/bin/env node
"use strict";

const path = require("path");

const { loadFrom, ConfigError } = require("./config");
const { build, prepare, BuildError } = require("./pipeline");
const { init } = require("./init");
const { PlaceholderError } = require("./placeholders");
const { VersionError } = require("./version");
const { forTargets, ClosureError } = require("./closure");
const { pack, PackError } = require("./pack");
const { changedSince, ChangedError } = require("./changed");
const { split, SplitError } = require("./split");
const { publish, ChannelError } = require("./channels");
const { VersionError: PolicyError, describe } = require("./version-policy");

const USAGE = `api-only-publisher -- build and distribute API description documents

Usage:
  api-only-publisher init [dir] [--force]
  api-only-publisher build [--target <name>]... [--version <v>] [--openapi|--asyncapi]
  api-only-publisher lint  [--target <name>]...
  api-only-publisher targets
  api-only-publisher closure [--target <name>]...
  api-only-publisher changed --since <ref>
  api-only-publisher pack --version <v> [--target <name>]... [--out <dir>]
  api-only-publisher publish --version <v> [--channel <name>]... [--out <dir>]
  api-only-publisher split --out <dir> [--by kind|target]

Options:
  --target <name>   Restrict to one target; repeat for several. Default: all.
  --version <v>     Stamp info.version on every built document.
  --openapi         Only build OpenAPI documents.
  --asyncapi        Only build AsyncAPI documents.
  --force           init only: overwrite files that already exist.
  --since <ref>     changed only: the git ref to compare the working tree against.
  --out <dir>       pack/publish/split: where to write.
  --channel <name>  publish only: repeat for several. Default: every configured channel.
  --by <kind|target>  split only: the boundary to split along. Default: kind.
  -C <dir>          Run as if started in <dir>.
  -q, --quiet       Only report errors.
  -h, --help        Show this help.

What gets built, and where each document goes, is declared in apionly.yaml.
`;

function parseArgs(argv) {
    const options = {
        targets: [], kinds: null, version: null, quiet: false, force: false,
        dir: process.cwd(), since: null, out: null, channels: null, by: "kind",
    };
    const positional = [];

    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i];
        const next = () => {
            if (i + 1 >= argv.length) throw new ConfigError(`${arg} requires a value`);
            return argv[++i];
        };
        switch (arg) {
            case "--target": options.targets.push(next()); break;
            case "--version": options.version = next(); break;
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
            default:
                if (arg.startsWith("-")) throw new ConfigError(`unrecognized option '${arg}'`);
                positional.push(arg);
        }
    }
    return { options, positional };
}

async function main(argv) {
    const { options, positional } = parseArgs(argv);
    const command = positional[0];

    if (options.help || !command) {
        process.stdout.write(USAGE);
        return 0;
    }

    const log = options.quiet ? () => {} : (message) => console.log(message);

    if (command === "init") {
        const dir = path.resolve(options.dir, positional[1] || ".");
        log(`Scaffolding a specification library in ${dir}`);
        init(dir, { force: options.force, log });
        log(`\nNext: api-only-publisher build -C ${dir}`);
        return 0;
    }

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
            const results = build(config, { targets, version: options.version, kinds: options.kinds || undefined, log });
            log(`\nBuilt ${results.length} document(s).`);
            return 0;
        }
        case "lint": {
            // Lint without rebuilding, for fast local feedback on what is already
            // in dist/.
            const fs = require("fs");
            const { lint } = require("./pipeline");
            let linted = 0;
            for (const kind of ["openapi", "asyncapi"]) {
                for (const target of config.targetsFor(kind)) {
                    if (targets && !targets.includes(target)) continue;
                    const file = path.join(config.distDir(target), config.outputName(kind));
                    if (!fs.existsSync(file)) {
                        throw new BuildError(`${file} does not exist; run 'build' first`);
                    }
                    log(`=== ${target} (${kind}) ===`);
                    lint(config, kind, file, log, {
                        report: true,
                        reportFile: config.lintReport(target, kind),
                    });
                    linted += 1;
                }
            }
            log(`\nLinted ${linted} document(s).`);
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
            if (!options.version) throw new ConfigError("pack requires --version <v>");
            const outDir = path.resolve(options.dir, options.out || "build/packages");
            const closures = forTargets(config);
            let packed = 0;
            for (const target of Object.keys(config.targets)) {
                if (targets && !targets.includes(target)) continue;
                if (!config.isPublished(target)) {
                    log(`${target}: not packed (publish: false)`);
                    continue;
                }
                const closure = closures.get(target);
                pack(config, target, {
                    version: options.version,
                    closureSha256: closure ? closure.sha256 : null,
                    outDir, log,
                });
                packed += 1;
            }
            log(`\nPacked ${packed} target(s) into ${outDir}.`);
            return 0;
        }
        case "publish": {
            if (!options.version) throw new ConfigError("publish requires --version <v>");
            const outDir = path.resolve(options.dir, options.out || "build/packages");
            const configured = config.channels || {};
            const names = options.channels || Object.keys(configured);
            if (names.length === 0) {
                throw new ConfigError("no channels configured; add a `channels:` block or pass --channel");
            }
            const closures = forTargets(config);
            let published = 0;
            for (const target of Object.keys(config.targets)) {
                if (targets && !targets.includes(target)) continue;
                if (!config.isPublished(target)) continue;
                const closure = closures.get(target);
                // Packed once, then shipped unchanged to every channel.
                const { archive, manifest } = pack(config, target, {
                    version: options.version,
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
        error instanceof ChannelError || error instanceof PolicyError) {
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
