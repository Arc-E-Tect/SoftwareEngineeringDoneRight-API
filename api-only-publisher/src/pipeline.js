"use strict";

// The build pipeline: stage, substitute, bundle, stamp, lint, distribute.

const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");
const YAML = require("yaml");

const { substituteFile } = require("./placeholders");
const { stampFile } = require("./version");
const { generateAsyncApi, generateOpenApi, openapiPushDown, isAggregate } = require("./aggregate");
const { stampFiles, componentPaths, strayPaths, fragmentStamps, unresolvedStamps, FragmentPathError, KEY } = require("./fragment-paths");

class BuildError extends Error {}

function run(command, args, { quiet, reportFile = null } = {}) {
    try {
        const out = execFileSync(command, args, { encoding: "utf8", stdio: quiet ? "pipe" : "inherit" });
        if (reportFile) {
            fs.mkdirSync(path.dirname(reportFile), { recursive: true });
            fs.writeFileSync(reportFile, out);
        }
        return out;
    } catch (error) {
        const output = (error.stdout || "") + (error.stderr || "");
        if (reportFile) {
            fs.mkdirSync(path.dirname(reportFile), { recursive: true });
            fs.writeFileSync(reportFile, output);
        }
        throw new BuildError(
            `${command} ${args.join(" ")} failed` + (error.stdout ? `\n${error.stdout}` : "") +
            (error.stderr ? `\n${error.stderr}` : "")
        );
    }
}

/**
 * Copy the whole source root to the staging directory for one specification
 * type.
 *
 * The *whole* root, not just that type's subtree: the trees $ref each other --
 * the AsyncAPI event schemas reuse the OpenAPI common schemas -- so a partial
 * copy breaks those references. Its own staging root per type, so that building
 * one type never invalidates the other's staged tree.
 */
function stage(config, kind, log) {
    const from = config.sourceRoot();
    const to = config.stagingRoot(kind);
    log(`-- Staging ${path.relative(config.root, from)} -> ${path.relative(config.root, to)}`);
    fs.rmSync(to, { recursive: true, force: true });
    fs.mkdirSync(to, { recursive: true });
    fs.cpSync(from, to, { recursive: true });
    return to;
}

/**
 * Substitute placeholders across the staged tree, in place.
 *
 * Every YAML file is visited, not only the bundle roots, so a placeholder works
 * wherever it is written, and no file has to be preprocessed separately first.
 */
function substituteTree(config, kind, log) {
    const stagingRoot = config.stagingRoot(kind);
    const placeholders = config.defaults.placeholders || {};
    const strict = placeholders.strict !== false;

    let files = 0;
    let tokens = 0;
    const walk = (dir) => {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => (a.name < b.name ? -1 : 1))) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) walk(full);
            else if (entry.isFile() && /\.ya?ml$/.test(entry.name)) {
                const result = substituteFile(full, { searchRoot: stagingRoot, strict });
                if (result.resolved.length > 0) {
                    files += 1;
                    tokens += result.resolved.length;
                }
            }
        }
    };
    walk(stagingRoot);
    log(`-- Substituted ${tokens} placeholder(s) across ${files} file(s)`);
}

function bundle(config, target, kind, outFile, log, { stagingRoot } = {}) {
    const source = config.bundleRootPath(target, kind, stagingRoot);
    if (!fs.existsSync(source)) {
        throw new BuildError(`target '${target}': bundle root not found at ${source}`);
    }
    fs.mkdirSync(path.dirname(outFile), { recursive: true });
    log(`-- Bundling ${path.basename(source)}`);
    const tool = kind === "openapi" ? config.tool("redocly") : config.tool("asyncapi");
    run("npx", ["--yes", tool, "bundle", source, "--output", outFile], { quiet: true });
    if (!fs.existsSync(outFile)) {
        throw new BuildError(`target '${target}': ${tool} produced no output at ${outFile}`);
    }
}

/**
 * Bundle a target with x-fragment-path on every component built from a fragment.
 *
 * Two passes over two fresh copies of the staged tree, so the build's own staged
 * tree -- which every other target bundles from -- is never stamped. The first
 * stamps every fragment and learns which of them the bundler made components of.
 * The second stamps only those, so the document differs from an unstamped one by
 * exactly one line per such component.
 *
 * That holds as long as no component's fragment is also inlined somewhere else;
 * if one is, the inlined copy would carry the stamp too, and the build fails
 * rather than publish it.
 */
function bundleWithFragmentPaths(config, target, kind, outFile, log) {
    if (kind === "asyncapi") return bundleInlinedWithFragmentPaths(config, target, kind, outFile, log);
    const scratch = config.fragmentPathStaging(target);
    fs.rmSync(scratch, { recursive: true, force: true });

    const pass = (name, only) => {
        const root = path.join(scratch, name);
        fs.cpSync(config.stagingRoot(kind), root, { recursive: true });
        try {
            stampFiles(root, { only });
        } catch (error) {
            if (error instanceof FragmentPathError) throw new BuildError(`target '${target}': ${error.message}`);
            throw error;
        }
        const file = path.join(scratch, `${name}.yaml`);
        bundle(config, target, kind, file, () => {}, { stagingRoot: root });
        return YAML.parse(fs.readFileSync(file, "utf8"));
    };

    log(`-- Bundling ${path.basename(config.bundleRootPath(target, kind))} with ${KEY}`);
    const discovered = componentPaths(pass("discover", null));
    const fragments = new Set([...discovered.values()].filter(Boolean));
    const document = pass("stamp", fragments);

    const components = componentPaths(document);
    if ([...components.keys()].join("\n") !== [...discovered.keys()].join("\n")) {
        throw new BuildError(`target '${target}': stamping ${KEY} changed the bundler's component names`);
    }
    const stray = strayPaths(document);
    if (stray.length > 0) {
        throw new BuildError(
            `target '${target}': a component's fragment is also inlined elsewhere, so ${KEY} would appear ` +
            `off its component: ${stray.map((s) => `${s.at} (${s.path})`).join(", ")}`);
    }

    fs.mkdirSync(path.dirname(outFile), { recursive: true });
    fs.copyFileSync(path.join(scratch, "stamp.yaml"), outFile);
    log(`-- Stamped ${KEY} on ${[...components.values()].filter(Boolean).length} component(s)`);
}

/**
 * Bundle a target whose bundler inlines rather than hoists, with x-fragment-path on
 * every fragment it inlines.
 *
 * An AsyncAPI bundle has no components section: a message is inlined into its
 * channel, its payload into the message, and a schema the payload references into
 * the payload -- including one from the OpenAPI tree, which is how a shared fragment
 * travels. So there is nothing to discover and nothing to select: every fragment is
 * stamped, and the stamp arrives wherever the bundler put that fragment.
 *
 * The bundle root is the document, not a fragment of one, so it is left alone.
 */
function bundleInlinedWithFragmentPaths(config, target, kind, outFile, log) {
    const scratch = config.fragmentPathStaging(target);
    fs.rmSync(scratch, { recursive: true, force: true });
    const root = path.join(scratch, "stamp");
    fs.cpSync(config.stagingRoot(kind), root, { recursive: true });

    const bundleRoot = path.relative(root, config.bundleRootPath(target, kind, root)).split(path.sep).join("/");
    try {
        stampFiles(root, { except: new Set([bundleRoot]) });
    } catch (error) {
        if (error instanceof FragmentPathError) throw new BuildError(`target '${target}': ${error.message}`);
        throw error;
    }

    log(`-- Bundling ${path.basename(config.bundleRootPath(target, kind))} with ${KEY}`);
    const file = path.join(scratch, "stamp.yaml");
    bundle(config, target, kind, file, () => {}, { stagingRoot: root });
    const document = YAML.parse(fs.readFileSync(file, "utf8"));

    const stamps = fragmentStamps(document);
    const missing = unresolvedStamps(document, root);
    if (missing.length > 0) {
        throw new BuildError(
            `target '${target}': ${KEY} names a fragment that is not in the library: ` +
            missing.map((stamp) => `${stamp.at} (${stamp.path})`).join(", "));
    }

    fs.mkdirSync(path.dirname(outFile), { recursive: true });
    fs.copyFileSync(file, outFile);
    log(`-- Stamped ${KEY} on ${stamps.length} inlined fragment(s)`);
}

function lint(config, kind, file, log, { report = false, reportFile = null } = {}) {
    const tool = kind === "openapi" ? config.tool("redocly") : config.tool("asyncapi");
    const args = kind === "openapi"
        ? ["--yes", tool, "lint"].concat(config.lintConfig("openapi") ? ["--config", config.lintConfig("openapi")] : []).concat([file])
        : ["--yes", tool, "validate", file];
    log(`-- Validating ${path.basename(file)}`);
    const output = run("npx", args, { quiet: true, reportFile });
    if (report && output.trim()) log(output.trimEnd());
}

/**
 * Copy a built document to the project that implements the target.
 *
 * Transitional. A producer has no business knowing its consumers' directory
 * layouts; this exists only so that the build stays verifiable against the
 * recorded fixtures while the Subscriber is not yet in place.
 */
function distribute(config, target, kind, builtFile, log) {
    const destDir = config.destinationDir(target);
    if (!destDir) return null;
    if (!fs.existsSync(destDir)) {
        throw new BuildError(`target '${target}': destination directory does not exist: ${destDir}`);
    }
    const destFile = path.join(destDir, config.outputName(kind));
    fs.copyFileSync(builtFile, destFile);
    log(`-- Distributed to ${path.relative(config.root, destFile)}`);
    return destFile;
}

/**
 * Stage, substitute and generate, without bundling.
 *
 * Everything that reads the fragment graph -- closures, `changed`, `split` --
 * needs the tree in the state the bundler will see it: substituted, and with
 * generated aggregate bundle roots present. Doing it in one place is what stops
 * those commands from quietly disagreeing with `build` about what the library
 * contains.
 */
function prepare(config, { kinds = ["openapi", "asyncapi"], log = () => {} } = {}) {
    for (const kind of kinds) {
        const targets = config.targetsFor(kind);
        if (targets.length === 0) continue;
        stage(config, kind, log);
        substituteTree(config, kind, log);
        for (const target of targets) {
            if (!isAggregate(config, target, kind)) continue;
            if (kind === "asyncapi") {
                generateAsyncApi(config, target, { log });
            } else {
                generateOpenApi(config, target, { log });
            }
        }
    }
}

/**
 * Build every requested target.
 *
 * `versionOf(target)` names the version to stamp on a target's documents; a target
 * it returns nothing for keeps the version its source declares.
 *
 * @returns {Array<{target, kind, file, distributed}>}
 */
function build(config, { targets, versionOf = () => null, kinds = ["openapi", "asyncapi"], log = () => {} } = {}) {
    const results = [];
    for (const kind of kinds) {
        const all = config.targetsFor(kind).filter((t) => !targets || targets.includes(t));
        if (all.length === 0) continue;

        log(`=== ${kind} ===`);
        stage(config, kind, log);
        substituteTree(config, kind, log);

        for (const target of all) {
            log(`\n=== ${target} (${kind}) ===`);
            // An aggregate has no hand-written bundle root; it is synthesised from
            // its members into the staged tree, so it can never fall behind them.
            let openapiOwners = null;
            if (isAggregate(config, target, kind)) {
                if (kind === "asyncapi") {
                    generateAsyncApi(config, target, { log });
                } else {
                    openapiOwners = generateOpenApi(config, target, { log }).owners;
                }
            }
            const outFile = path.join(config.distDir(target), config.outputName(kind));
            if (config.fragmentPaths(kind)) {
                bundleWithFragmentPaths(config, target, kind, outFile, log);
            } else {
                bundle(config, target, kind, outFile, log);
            }
            if (openapiOwners) {
                // Pass 2: only once bundling has resolved every $ref does an
                // operation's id, or whether it already sets its own security,
                // become visible at all.
                const { text, reconciled } = openapiPushDown(fs.readFileSync(outFile, "utf8"), openapiOwners, {
                    operationIdStrategy: config.portfolioOperationIdStrategy(),
                });
                fs.writeFileSync(outFile, text);
                for (const r of reconciled) {
                    log(`-- Reconciled tag '${r.key}': two members describe it differently; kept the first's`);
                }
            }
            const version = versionOf(target);
            if (version) {
                log(`-- Stamping version '${version}'`);
                stampFile(outFile, version);
            }
            lint(config, kind, outFile, log);

            let distributed = null;
            if (config.isPublished(target)) {
                distributed = distribute(config, target, kind, outFile, log);
            } else {
                log("-- Not distributed (publish: false)");
            }
            results.push({ target, kind, file: outFile, distributed });
        }
    }
    return results;
}

module.exports = { build, prepare, stage, substituteTree, bundle, bundleWithFragmentPaths, lint, distribute, BuildError };
