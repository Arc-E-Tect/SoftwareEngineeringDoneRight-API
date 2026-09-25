"use strict";

// Reading and validating apionly.yaml.
//
// The configuration is the single source of truth for what a library builds:
// which targets exist, where their fragments live, and where each document goes.

const fs = require("fs");
const path = require("path");
const YAML = require("yaml");

const CONFIG_NAME = "apionly.yaml";
const SUPPORTED_SCHEMA_VERSION = 1;

// The keys allowed at the levels of apionly.yaml that have a fixed shape. `channels`
// and `toolchain` are deliberately absent: a channel's settings depend on its type,
// and a tool's name is whatever the author calls it, so neither has a closed
// vocabulary to check against. Everywhere else, a key nothing reads is far more
// often a typo or a bad indent than a value nobody has used yet -- see checkKeys.
const ROOT_KEYS = [
    "schemaVersion", "sources", "defaults", "build", "reports", "lint",
    "distribution", "channels", "targets", "toolchain", "portfolio",
];
const SOURCES_KEYS = ["root", "openapi", "asyncapi"];
const DEFAULTS_KEYS = ["openapi", "asyncapi", "placeholders"];
const DEFAULTS_KIND_KEYS = { openapi: ["lint", "outputName", "fragmentPaths"], asyncapi: ["lint", "outputName", "fragmentPaths"] };
const PLACEHOLDERS_KEYS = ["strict"];
const BUILD_KEYS = ["staging", "dist", "packages", "split"];
const REPORTS_KEYS = ["lint"];
const LINT_KEYS = ["unreferenced", "examples"];
const LINT_EXAMPLES_KEYS = ["openapi", "asyncapi"];
const DISTRIBUTION_KEYS = ["root", "layout"];
const TARGET_KEYS = ["openapi", "asyncapi", "publish", "versionFile"];
const TARGET_KIND_KEYS = ["bundle", "aggregate", "info"];
const PORTFOLIO_KEYS = ["openapi", "security", "location"];
const PORTFOLIO_OPENAPI_KEYS = ["paths", "operationIds", "tags"];

// What every portfolio setting is when apionly.yaml says nothing: safe for the
// ordinary case (a gateway routing /<target>/** to each service), and named rather
// than boolean, so a third strategy can be added later without breaking the schema.
// Because a portfolio is regenerated on every build, none of this needs a migration
// when it changes -- the next build simply produces a different portfolio.
const PORTFOLIO_DEFAULTS = Object.freeze({
    openapi: Object.freeze({ paths: "target-prefix", operationIds: "target-prefix", tags: "reconcile" }),
    security: "push-down",
    location: "portfolios",
});
const PATH_STRATEGIES = ["target-prefix", "none"];

class ConfigError extends Error {}

/** Refuses a key `obj` has that is not in `allowed`, naming the key and where it is. */
function checkKeys(obj, allowed, where) {
    if (!obj || typeof obj !== "object") return;
    for (const key of Object.keys(obj)) {
        if (!allowed.includes(key)) {
            throw new ConfigError(`unknown key '${key}' in ${where}`);
        }
    }
}

// Walk up from `startDir` looking for apionly.yaml, so the CLI can be run from
// anywhere inside the specification repository.
function locate(startDir) {
    let dir = path.resolve(startDir);
    for (;;) {
        const candidate = path.join(dir, CONFIG_NAME);
        if (fs.existsSync(candidate)) return candidate;
        const parent = path.dirname(dir);
        if (parent === dir) {
            throw new ConfigError(
                `no ${CONFIG_NAME} found in ${path.resolve(startDir)} or any parent directory`
            );
        }
        dir = parent;
    }
}

function requireString(value, what) {
    if (typeof value !== "string" || value.trim() === "") {
        throw new ConfigError(`${what} must be a non-empty string`);
    }
    return value;
}

function load(configPath) {
    const raw = fs.readFileSync(configPath, "utf8");
    let parsed;
    try {
        parsed = YAML.parse(raw);
    } catch (error) {
        throw new ConfigError(`${configPath} is not valid YAML: ${error.message}`);
    }
    if (parsed === null || typeof parsed !== "object") {
        throw new ConfigError(`${configPath} is empty`);
    }

    if (parsed.schemaVersion !== SUPPORTED_SCHEMA_VERSION) {
        throw new ConfigError(
            `${configPath} declares schemaVersion ${JSON.stringify(parsed.schemaVersion)}; ` +
            `this version of api-only-publisher understands ${SUPPORTED_SCHEMA_VERSION}`
        );
    }

    checkKeys(parsed, ROOT_KEYS, CONFIG_NAME);

    const root = path.dirname(configPath);
    const sources = parsed.sources || {};
    checkKeys(sources, SOURCES_KEYS, "sources");
    requireString(sources.root, "sources.root");

    const defaults = parsed.defaults || {};
    checkKeys(defaults, DEFAULTS_KEYS, "defaults");
    for (const kind of ["openapi", "asyncapi"]) {
        if (defaults[kind]) checkKeys(defaults[kind], DEFAULTS_KIND_KEYS[kind], `defaults.${kind}`);
    }
    if (defaults.placeholders) checkKeys(defaults.placeholders, PLACEHOLDERS_KEYS, "defaults.placeholders");

    const build = parsed.build || {};
    checkKeys(build, BUILD_KEYS, "build");
    const reports = parsed.reports || {};
    checkKeys(reports, REPORTS_KEYS, "reports");
    const lint = parsed.lint || {};
    checkKeys(lint, LINT_KEYS, "lint");
    if (lint.examples) checkKeys(lint.examples, LINT_EXAMPLES_KEYS, "lint.examples");
    if (parsed.distribution) checkKeys(parsed.distribution, DISTRIBUTION_KEYS, "distribution");

    const portfolio = parsed.portfolio || {};
    checkKeys(portfolio, PORTFOLIO_KEYS, "portfolio");
    if (portfolio.openapi) checkKeys(portfolio.openapi, PORTFOLIO_OPENAPI_KEYS, "portfolio.openapi");
    const pathStrategy = portfolio.openapi && portfolio.openapi.paths;
    if (pathStrategy !== undefined && !PATH_STRATEGIES.includes(pathStrategy)) {
        throw new ConfigError(
            `portfolio.openapi.paths must be ${PATH_STRATEGIES.join(" or ")}, not ${JSON.stringify(pathStrategy)}`
        );
    }

    const targets = parsed.targets;
    if (!targets || typeof targets !== "object" || Object.keys(targets).length === 0) {
        throw new ConfigError(`${configPath} declares no targets`);
    }

    // Declaration order is preserved deliberately: it is what makes one run's
    // console output comparable with the next.
    for (const [name, target] of Object.entries(targets)) {
        if (!target || typeof target !== "object") {
            throw new ConfigError(`target '${name}' is not a mapping`);
        }
        checkKeys(target, TARGET_KEYS, `targets.${name}`);
        const kinds = ["openapi", "asyncapi"].filter((k) => target[k]);
        if (kinds.length === 0) {
            throw new ConfigError(
                `target '${name}' declares neither an openapi nor an asyncapi bundle`
            );
        }

        // A portfolio is a catalogue, never a contract of its own: declaring an
        // aggregate for one kind means the target IS a portfolio, so it must
        // aggregate every kind it declares, and it is never published.
        const aggregateKinds = kinds.filter((k) => target[k].aggregate !== undefined);
        if (aggregateKinds.length > 0 && aggregateKinds.length !== kinds.length) {
            const bundled = kinds.find((k) => target[k].aggregate === undefined);
            throw new ConfigError(
                `target '${name}' aggregates ${aggregateKinds[0]} but has a hand-written bundle for ${bundled}; ` +
                "a target may not mix an aggregate with a hand-written bundle across kinds -- a hand-written " +
                "AsyncAPI portfolio is an ordinary bundle, not an aggregate that happens to look like one"
            );
        }
        if (aggregateKinds.length > 0 && target.publish === true) {
            throw new ConfigError(
                `target '${name}' declares an aggregate; it is never published, whatever publish says. ` +
                "Prefixed operation ids match no member's, transcribed classes would not match a member's " +
                "either, and a portfolio's version answers no useful question, since it moves whenever " +
                "anything anywhere moves. A combined surface that genuinely needs publishing is a bundle " +
                "somebody authors deliberately, with its own owner and version -- author one instead."
            );
        }

        for (const kind of kinds) {
            checkKeys(target[kind], TARGET_KIND_KEYS, `targets.${name}.${kind}`);
            if (target[kind].aggregate !== undefined) {
                if (target[kind].bundle !== undefined) {
                    throw new ConfigError(
                        `target '${name}' declares both aggregate and bundle for ${kind}; an aggregate is ` +
                        "generated whole, so it needs no hand-written bundle root"
                    );
                }
                const members = target[kind].aggregate;
                if (!Array.isArray(members) || members.length === 0) {
                    throw new ConfigError(`targets.${name}.${kind}.aggregate must list at least one target`);
                }
                if (members.includes(name)) {
                    throw new ConfigError(`target '${name}' aggregates itself`);
                }
            } else {
                requireString(target[kind].bundle, `targets.${name}.${kind}.bundle`);
                if (target[kind].info !== undefined) {
                    throw new ConfigError(`targets.${name}.${kind}.info only applies to an aggregate`);
                }
            }
        }
    }

    // Every member an aggregate lists is checked by name, and a member that is
    // itself an aggregate is refused outright: it has no hand-written bundle for
    // the generator to read, and reading its *generated* one would mean generating
    // aggregates in dependency order, which nothing here attempts. Because an
    // aggregate can only ever reach a hand-written bundle this way, refusing that
    // one step also catches every transitive self-reference, not only the direct
    // one the loop above already refused.
    for (const [name, target] of Object.entries(targets)) {
        for (const kind of ["openapi", "asyncapi"].filter((k) => target[k] && target[k].aggregate !== undefined)) {
            for (const member of target[kind].aggregate) {
                const memberTarget = targets[member];
                if (!memberTarget || memberTarget[kind] === undefined) {
                    throw new ConfigError(
                        `target '${name}': aggregate member '${member}' declares no ${kind} bundle`
                    );
                }
                if (memberTarget[kind].aggregate !== undefined) {
                    throw new ConfigError(
                        `target '${name}' aggregates '${member}', which is itself an aggregate; ` +
                        "nesting is not supported"
                    );
                }
            }
        }
    }

    return {
        path: configPath,
        root,
        schemaVersion: parsed.schemaVersion,
        sources,
        defaults,
        toolchain: parsed.toolchain || {},
        build,
        reports,
        lint,
        distribution: parsed.distribution || null,
        channels: parsed.channels || {},
        portfolio,
        targets,

        // --- derived accessors, so callers never re-derive a path themselves ---

        sourceRoot() {
            return path.resolve(this.root, this.sources.root);
        },
        stagingRoot(kind) {
            const staging = this.build.staging || "build/staging";
            return path.resolve(this.root, staging, kind);
        },
        // The subtree of the staged mirror that holds one specification type.
        stagingDir(kind) {
            return path.join(this.stagingRoot(kind), requireString(this.sources[kind], `sources.${kind}`));
        },
        // Where pack and publish write archives, and split its parts, unless --out says
        // otherwise; relative to this configuration, as every other path here is.
        packagesDir() {
            return path.resolve(this.root, this.build.packages || "build/packages");
        },
        splitDir() {
            return path.resolve(this.root, this.build.split || "build/split");
        },
        distDir(target) {
            const dist = this.build.dist || "dist";
            return path.resolve(this.root, dist, target);
        },
        outputName(kind) {
            const name = (this.defaults[kind] || {}).outputName;
            return requireString(name, `defaults.${kind}.outputName`);
        },
        lintConfig(kind) {
            const lint = (this.defaults[kind] || {}).lint;
            return lint ? path.resolve(this.root, lint) : null;
        },
        lintReport(target, kind) {
            const reports = this.reports.lint || "build/reports/lint";
            return path.resolve(this.root, reports, target, `${kind}.txt`);
        },
        // Where lint lists the fragments no target reaches.
        unreferencedReport() {
            const reports = this.reports.lint || "build/reports/lint";
            return path.resolve(this.root, reports, "unreferenced.txt");
        },
        // What lint does about a fragment no target reaches: `error`, the default,
        // fails the lint; `warn` reports it; `off` does not look.
        lintUnreferenced() {
            const configured = this.lint.unreferenced;
            const mode = configured === undefined ? "error" : configured === false ? "off" : configured;
            if (!["error", "warn", "off"].includes(mode)) {
                throw new ConfigError(`lint.unreferenced must be error, warn or off, not ${JSON.stringify(configured)}`);
            }
            return mode;
        },
        // What the build does about an operation whose example this kind's
        // toolchain could use, and does not have: `warn`, the default, reports it;
        // `error` fails the build; `off` does not look. Examples are never
        // mandatory to the specification, so the default suits a library that has
        // not adopted Microcks; a library that has wants `error` for asyncapi.
        lintExamples(kind) {
            const configured = (this.lint.examples || {})[kind];
            const mode = configured === undefined ? "warn" : configured;
            if (!["error", "warn", "off"].includes(mode)) {
                throw new ConfigError(
                    `lint.examples.${kind} must be error, warn or off, not ${JSON.stringify(configured)}`);
            }
            return mode;
        },
        tool(name) {
            return requireString(this.toolchain[name], `toolchain.${name}`);
        },
        // Targets declaring a bundle of this kind, in declaration order.
        targetsFor(kind) {
            return Object.keys(this.targets).filter((t) => this.targets[t][kind]);
        },
        // Whether this target is itself an aggregate, for any kind it declares.
        // load() already refuses a target that aggregates one kind and hand-writes
        // another, so any one kind answers for the whole target.
        isAggregate(target) {
            return ["openapi", "asyncapi"].some((k) => this.targets[target][k] && this.targets[target][k].aggregate !== undefined);
        },
        // A target with `publish: false` is a documentation view rather than a
        // contract any one project implements: built and linted, never shipped.
        // An aggregate is never published either, whether or not it says so --
        // load() already refuses the one case that would contradict this, `publish:
        // true` alongside an aggregate, so nothing here needs to re-check that.
        isPublished(target) {
            return this.targets[target].publish !== false && !this.isAggregate(target);
        },
        // A target's bundle root in a staged tree: the build's own, unless another
        // staged copy is named. Only a hand-written bundle has one; an aggregate's
        // generated root is aggregatePath(), below.
        bundlePath(target, kind, stagingRoot = this.stagingRoot(kind)) {
            return path.join(
                stagingRoot,
                requireString(this.sources[kind], `sources.${kind}`),
                this.targets[target][kind].bundle);
        },
        // Where an aggregate's generated bundle root lands in a staged tree: outside
        // bundles/, at the same depth, so every $ref a member contributes -- carried
        // over verbatim -- still resolves. The path is the tool's to decide, not the
        // author's: an aggregate declares its members and its info, never where its
        // generated root lands.
        aggregatePath(target, kind, stagingRoot = this.stagingRoot(kind)) {
            return path.join(
                stagingRoot,
                requireString(this.sources[kind], `sources.${kind}`),
                this.portfolioLocation(),
                `${target}_${kind}_structure.yaml`);
        },
        // Where a target's document root sits in a staged tree, hand-written or
        // generated -- whichever this target is.
        bundleRootPath(target, kind, stagingRoot = this.stagingRoot(kind)) {
            return this.isAggregate(target)
                ? this.aggregatePath(target, kind, stagingRoot)
                : this.bundlePath(target, kind, stagingRoot);
        },
        // The path prefix strategy an OpenAPI portfolio merges its members' paths
        // and operation ids with: `target-prefix` (the default) for a gateway
        // routing /<target>/** to each service, `none` where routing is by host and
        // a prefixed path would exist nowhere.
        portfolioPathStrategy() {
            return (this.portfolio.openapi && this.portfolio.openapi.paths) || PORTFOLIO_DEFAULTS.openapi.paths;
        },
        // How a portfolio's operation ids are told apart: currently always prefixed
        // with the target, the same way paths are -- named, like every portfolio
        // setting, so a second strategy can be added later without a breaking change.
        portfolioOperationIdStrategy() {
            return (this.portfolio.openapi && this.portfolio.openapi.operationIds) || PORTFOLIO_DEFAULTS.openapi.operationIds;
        },
        // How two members contributing the same tag name are merged: `reconcile`,
        // today the only mode -- an identical body merges silently, a differing one
        // merges too, reported, with the first contributing member's body winning.
        portfolioTagStrategy() {
            return (this.portfolio.openapi && this.portfolio.openapi.tags) || PORTFOLIO_DEFAULTS.openapi.tags;
        },
        // How a portfolio merges root-level `security`: `push-down`, today the only
        // mode -- each member's root security becomes each of its own contributed
        // operations' security, so the portfolio never has to say something false
        // about any one of them. See the Security section of the README.
        portfolioSecurityStrategy() {
            return this.portfolio.security || PORTFOLIO_DEFAULTS.security;
        },
        // The directory an aggregate's generated bundle root lands in, sibling to
        // bundles/, at the same depth under each kind's own source tree.
        portfolioLocation() {
            return this.portfolio.location || PORTFOLIO_DEFAULTS.location;
        },
        // Whether built documents of this kind carry x-fragment-path: on each
        // component of an OpenAPI document, on each fragment of an AsyncAPI one.
        // On unless turned off, for both kinds.
        fragmentPaths(kind) {
            if (kind !== "openapi" && kind !== "asyncapi") return false;
            const configured = (this.defaults[kind] || {}).fragmentPaths;
            if (configured === undefined) return true;
            if (typeof configured !== "boolean") {
                throw new ConfigError(
                    `defaults.${kind}.fragmentPaths must be true or false, not ${JSON.stringify(configured)}`);
            }
            return configured;
        },
        // Where one target's stamped copies of the staged tree are built.
        fragmentPathStaging(target) {
            const staging = this.build.staging || "build/staging";
            return path.resolve(this.root, staging, "fragment-paths", target);
        },
        // The file a target's version is read from: the target's own `versionFile`,
        // relative to this configuration, or else <target>.bundle.properties beside
        // the target's first bundle root in the hand-authored tree -- so a version
        // sits with the fragments it describes, and changes in the same commit.
        versionFile(target) {
            const spec = this.targets[target];
            if (spec.versionFile !== undefined) {
                return path.resolve(this.root, requireString(spec.versionFile, `targets.${target}.versionFile`));
            }
            const kind = ["openapi", "asyncapi"].find((k) => spec[k]);
            const bundleRoot = path.join(
                this.sourceRoot(), requireString(this.sources[kind], `sources.${kind}`), spec[kind].bundle);
            return path.join(path.dirname(bundleRoot), `${target}.bundle.properties`);
        },
        // Where a distributed document is copied to, from the transitional
        // `distribution` block. Null once that block is gone.
        destinationDir(target) {
            if (!this.distribution) return null;
            const layout = requireString(this.distribution.layout, "distribution.layout");
            return path.resolve(
                this.root,
                this.distribution.root || ".",
                layout.replace(/\{target\}/g, target)
            );
        },
    };
}

function loadFrom(startDir) {
    return load(locate(startDir));
}

module.exports = { load, loadFrom, locate, ConfigError, CONFIG_NAME, SUPPORTED_SCHEMA_VERSION };
