"use strict";

// Reading and validating apionly.yaml.
//
// The configuration is the single source of truth for what this library builds:
// it replaced the per-script SERVICES arrays, and the `apis:` map that said an
// overlapping thing under a different name.

const fs = require("fs");
const path = require("path");
const YAML = require("yaml");

const CONFIG_NAME = "apionly.yaml";
const SUPPORTED_SCHEMA_VERSION = 1;

class ConfigError extends Error {}

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

    const root = path.dirname(configPath);
    const sources = parsed.sources || {};
    requireString(sources.root, "sources.root");

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
        const kinds = ["openapi", "asyncapi"].filter((k) => target[k]);
        if (kinds.length === 0) {
            throw new ConfigError(
                `target '${name}' declares neither an openapi nor an asyncapi bundle`
            );
        }
        for (const kind of kinds) {
            requireString(target[kind].bundle, `targets.${name}.${kind}.bundle`);
        }
    }

    return {
        path: configPath,
        root,
        schemaVersion: parsed.schemaVersion,
        sources,
        defaults: parsed.defaults || {},
        toolchain: parsed.toolchain || {},
        build: parsed.build || {},
        reports: parsed.reports || {},
        distribution: parsed.distribution || null,
        channels: parsed.channels || {},
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
        tool(name) {
            return requireString(this.toolchain[name], `toolchain.${name}`);
        },
        // Targets declaring a bundle of this kind, in declaration order.
        targetsFor(kind) {
            return Object.keys(this.targets).filter((t) => this.targets[t][kind]);
        },
        // A target with `publish: false` is a documentation view rather than a
        // contract any one project implements: built and linted, never shipped.
        isPublished(target) {
            return this.targets[target].publish !== false;
        },
        bundlePath(target, kind) {
            return path.join(this.stagingDir(kind), this.targets[target][kind].bundle);
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
