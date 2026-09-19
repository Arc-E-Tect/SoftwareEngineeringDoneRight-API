"use strict";

// `init` scaffolds a specification repository.
//
// This is how the *conventions* become reusable, as opposed to the code. The
// layout below is the one the library documentation describes; a project that
// starts from it inherits the common/<product> split, the shared info block with
// placeholder snippets, and a configuration that already builds.
//
// What it writes follows from a handful of values -- which kinds of document the
// library holds, the target's name, the API's title and so on -- that
// init-questions.js asks for at a terminal and defaults everywhere else. This file
// only turns values into files and writes them; it never reads input itself.

const fs = require("fs");
const path = require("path");
const YAML = require("yaml");
const { addMissingConfig } = require("./init-config");

/** Every value the scaffold is written from, as it is when nobody chooses otherwise. */
const DEFAULTS = Object.freeze({
    kinds: Object.freeze(["openapi"]),
    target: "example-service",
    title: "Example API",
    contractVersion: "0.1.0",
    contactName: "Example Team",
    contactUrl: "https://example.invalid",
    license: "Apache-2.0",
    licenseUrl: "https://www.apache.org/licenses/LICENSE-2.0.html",
    serverUrl: "https://api.example.invalid",
    brokerHost: "kafka:9092",
});

/** A value as a YAML scalar: plain where YAML reads it back unchanged, quoted where it would not. */
function scalar(value) {
    return YAML.stringify(value, { lineWidth: 0 }).trimEnd();
}

function config(v) {
    const openapi = v.kinds.includes("openapi");
    const asyncapi = v.kinds.includes("asyncapi");
    const lines = [
        "# apionly.yaml",
        "#",
        "# Declares what this library builds, from where, and where each document goes.",
        "",
        "schemaVersion: 1",
        "",
        "sources:",
        "  root: specs",
    ];
    if (openapi) lines.push("  openapi: openapi");
    if (asyncapi) lines.push("  asyncapi: asyncapi");
    lines.push("", "defaults:");
    if (openapi) lines.push("  openapi:", "    lint: .redocly.yaml", "    outputName: openapi.yaml");
    if (asyncapi) lines.push("  asyncapi:", "    outputName: asyncapi.yaml");
    lines.push(
        "  placeholders:",
        "    # Fail the build on a {{token}} with no matching Markdown file, rather than",
        "    # emitting a marker into a published contract.",
        "    strict: true",
        "",
        "build:",
        "  staging: build/staging",
        "  dist: dist",
        "",
        "toolchain:",
    );
    if (openapi) lines.push('  redocly: "@redocly/cli@2.52.0"');
    if (asyncapi) lines.push('  asyncapi: "@asyncapi/cli@6.0.2"');
    lines.push("", "targets:", `  ${v.target}:`);
    if (openapi) lines.push("    openapi:", `      bundle: bundles/${v.target}_openapi_structure.yaml`);
    if (asyncapi) lines.push("    asyncapi:", `      bundle: bundles/${v.target}_asyncapi_structure.yaml`);
    return lines.join("\n") + "\n";
}

const info = (v) => `title: ${scalar(v.title)}
version: 0.0.0
description: |
  What this API is for.

  {{conventions}}
contact:
  name: ${scalar(v.contactName)}
  url: ${scalar(v.contactUrl)}
license:
  name: ${scalar(v.license)}
  url: ${scalar(v.licenseUrl)}
`;

const CONVENTIONS = `## Conventions

Prose shared by every document in this library lives in a Markdown snippet like
this one, pulled into the specification by a placeholder token. Replace this file
with whatever your own API consumers need to know up front -- pagination, status
codes, error shapes.
`;

const servers = (v) => `- url: ${scalar(v.serverUrl)}
  description: Production.
`;

const BUNDLE = `openapi: 3.1.1
info:
  $ref: '../shared/info.yaml'
servers:
  $ref: '../shared/servers.yaml'
security:
  - bearerAuth: []
tags: []
paths:
  /v1/examples:
    $ref: '../paths/example/ExamplesV1.yaml'
components:
  securitySchemes:
    bearerAuth:
      $ref: '../components/common/security/BearerAuth.yaml'
`;

const SECURITY_SCHEME = `type: http
scheme: bearer
bearerFormat: JWT
`;

const PROBLEM = `description: The request was not valid.
content:
  application/problem+json:
    schema:
      # RFC 9457. A problem shape is product-wide infrastructure, so it lives
      # under components/common/ however service-specific its meaning.
      type: object
      properties:
        type:
          type: string
          format: uri
        title:
          type: string
        status:
          type: integer
        detail:
          type: string
      required:
        - type
        - title
        - status
`;

// With AsyncAPI beside it, the examples are identified by a schema both protocols
// share; on its own, the OpenAPI scaffold stays as small as it always was.
const pathFragment = (shared) => `get:
  operationId: listExamples
  summary: List examples.
  responses:
    '200':
      description: The examples.
      content:
        application/json:
          schema:
            type: array
            items:
${shared ? "              $ref: '../../components/common/schemas/ExampleIdV1.yaml'" : "              type: string"}
    '400':
      $ref: '../../components/common/responses/errors/InvalidRequestProblemV1.yaml'
`;

const EXAMPLE_ID = `# One definition, two protocols: the HTTP response and the event both refer to this
# fragment, so an example's identifier means the same thing wherever it appears.
type: string
description: An example's identifier.
`;

const REDOCLY = `# Lint rules for this library.
#
# 'recommended' is Redocly's own baseline. Narrow or widen it as the library
# grows -- these rules are the governance the library applies to itself, which is
# why they live here rather than in any project that consumes the output.
extends:
  - recommended
`;

const GITIGNORE = `build/
dist/
node_modules/
`;

const version = (v) => `# The version of the ${v.target} contract, for every document it builds.
# Semantic: major for a breaking change, minor for an additive one, patch for
# anything else. Change it in the same commit as the fragments it describes.
version=${v.contractVersion}
`;

// The AsyncAPI bundle root holds its operations itself: they point into the document
// with #/channels/..., which only resolves in the file that contains it.
const asyncBundle = (v) => `asyncapi: 3.1.0
info:
  title: ${scalar(v.title)}
  version: 0.0.0
  description: |
    The events this API publishes.

    {{conventions}}
  contact:
    name: ${scalar(v.contactName)}
    url: ${scalar(v.contactUrl)}
  license:
    name: ${scalar(v.license)}
    url: ${scalar(v.licenseUrl)}
defaultContentType: application/json
servers:
  production:
    host: ${scalar(v.brokerHost)}
    protocol: kafka
    description: Production.
channels:
  examplesV1:
    $ref: '../channels/example/ExamplesV1.yaml'
operations:
  publishExampleCreated:
    action: send
    channel:
      $ref: '#/channels/examplesV1'
    summary: Publish an ExampleCreated event when an example is created.
    messages:
      - $ref: '#/channels/examplesV1/messages/exampleCreated'
`;

const CHANNEL = `address: examples.v1
title: Examples
description: Events about examples.
messages:
  exampleCreated:
    $ref: '../../messages/example/ExampleCreatedMessageV1.yaml'
`;

const MESSAGE = `name: ExampleCreated
title: An example was created.
contentType: application/json
payload:
  $ref: '../../components/example/schemas/ExampleCreatedEventV1.yaml'
`;

const event = (shared) => `type: object
description: An example was created.
required:
  - id
  - occurredAt
properties:
  id:
${shared
        ? "    # The OpenAPI tree's schema: an event and an HTTP response mean one identifier.\n" +
          "    $ref: '../../../../openapi/components/common/schemas/ExampleIdV1.yaml'"
        : "    type: string\n    description: The example's identifier."}
  occurredAt:
    type: string
    format: date-time
    description: When the example was created.
`;

/**
 * The files a library of these values starts with, keyed by their path relative to it.
 *
 * @param {object} values the values, as DEFAULTS has them
 * @returns {Object<string, string>}
 */
function scaffold(values = DEFAULTS) {
    const v = { ...DEFAULTS, ...values };
    const openapi = v.kinds.includes("openapi");
    const asyncapi = v.kinds.includes("asyncapi");
    const both = openapi && asyncapi;
    const files = { "apionly.yaml": config(v) };
    if (openapi) files[".redocly.yaml"] = REDOCLY;
    files[".gitignore"] = GITIGNORE;

    if (openapi) {
        Object.assign(files, {
            "specs/openapi/shared/info.yaml": info(v),
            "specs/openapi/shared/conventions.md": CONVENTIONS,
            "specs/openapi/shared/servers.yaml": servers(v),
            [`specs/openapi/bundles/${v.target}_openapi_structure.yaml`]: BUNDLE,
            [`specs/openapi/bundles/${v.target}.bundle.properties`]: version(v),
            "specs/openapi/paths/example/ExamplesV1.yaml": pathFragment(both),
            "specs/openapi/components/common/security/BearerAuth.yaml": SECURITY_SCHEME,
            "specs/openapi/components/common/responses/errors/InvalidRequestProblemV1.yaml": PROBLEM,
        });
        if (both) files["specs/openapi/components/common/schemas/ExampleIdV1.yaml"] = EXAMPLE_ID;
    }
    if (asyncapi) {
        // The version file sits beside the target's first bundle root: the OpenAPI one
        // when there is one. The conventions snippet is found anywhere under specs/.
        if (!openapi) {
            files[`specs/asyncapi/bundles/${v.target}.bundle.properties`] = version(v);
            files["specs/asyncapi/shared/conventions.md"] = CONVENTIONS;
        }
        Object.assign(files, {
            [`specs/asyncapi/bundles/${v.target}_asyncapi_structure.yaml`]: asyncBundle(v),
            "specs/asyncapi/channels/example/ExamplesV1.yaml": CHANNEL,
            "specs/asyncapi/messages/example/ExampleCreatedMessageV1.yaml": MESSAGE,
            "specs/asyncapi/components/example/schemas/ExampleCreatedEventV1.yaml": event(both),
        });
    }
    return files;
}

const FILES = scaffold(DEFAULTS);

/** Text as a comparison sees it: line endings and final newlines are not differences. */
function normalised(text) {
    return text.replace(/\r\n?/g, "\n").replace(/\n+$/, "");
}

const CONFIG_FILE = "apionly.yaml";

/**
 * What writing these files would do to each: create it, leave it because it is
 * identical, or find it different from what would be written.
 *
 * apionly.yaml gets one more thing tried, ahead of "differs": whatever slot
 * scaffold(values) would write and it lacks is added, in place, before the
 * comparison that decides "identical" or "differs" -- so a file that is missing only
 * a kind it never had is never reported as differing, and neither is one that is
 * missing nothing at all. `values`, resolved as scaffold() resolves it, is what that
 * completion is measured against; without it, apionly.yaml is compared as every
 * other file is.
 *
 * @returns {{rel: string, status: "missing"|"identical"|"differs", added?: string[], text?: string}[]}
 *     `added` and `text` -- the completed content -- are there only for apionly.yaml,
 *     and only when something was missing from it.
 */
function plan(targetDir, files, values) {
    return Object.entries(files).map(([rel, content]) => {
        const file = path.join(targetDir, rel);
        if (!fs.existsSync(file)) return { rel, status: "missing" };
        const onDisk = fs.readFileSync(file, "utf8");
        if (rel === CONFIG_FILE && values) {
            const completed = addMissingConfig(onDisk, { ...DEFAULTS, ...values });
            if (completed.added.length > 0) {
                const status = normalised(completed.text) === normalised(content) ? "identical" : "differs";
                return { rel, status, added: completed.added, text: completed.text };
            }
        }
        const same = normalised(onDisk) === normalised(content);
        return { rel, status: same ? "identical" : "differs" };
    });
}

/**
 * Writes the scaffold of these values into a directory, and reports each file.
 *
 * A file that is not there is created. One that is there already is left alone,
 * reported as identical to what would have been written or as differing from it;
 * with `force`, one that differs is overwritten, wholesale, as if this were the
 * first time -- apionly.yaml included, whatever else it declares.
 *
 * Short of `force`, apionly.yaml gets one more chance: whatever configuration a
 * missing kind needs and it lacks is added to it, leaving every value, key and
 * comment already there exactly as it was. A kind already declared, however it
 * reads, is never touched -- what is already there is not changed, only what is
 * not there is added.
 */
function init(targetDir, { values = DEFAULTS, force = false, log = () => {} } = {}) {
    const files = scaffold(values);
    const report = { created: [], overwritten: [], identical: [], differing: [], updated: [] };
    const updates = [];

    for (const { rel, status, added, text } of plan(targetDir, files, values)) {
        if (added && !force) {
            fs.writeFileSync(path.join(targetDir, rel), text);
            report.updated.push(rel);
            updates.push({ rel, added });
            continue;
        }
        if (status === "identical") {
            report.identical.push(rel);
            continue;
        }
        if (status === "differs" && !force) {
            report.differing.push(rel);
            continue;
        }
        const file = path.join(targetDir, rel);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, files[rel]);
        (status === "missing" ? report.created : report.overwritten).push(rel);
    }

    for (const rel of report.created) log(`  created    ${rel}`);
    for (const rel of report.overwritten) log(`  overwrote  ${rel}`);
    for (const { rel, added } of updates) log(`  updated    ${rel} (added ${added.join(", ")})`);
    for (const rel of report.identical) log(`  identical  ${rel}`);
    for (const rel of report.differing) log(`  differs    ${rel} (left alone; --force overwrites)`);
    return { ...report, skipped: [...report.identical, ...report.differing] };
}

module.exports = { init, scaffold, plan, FILES, DEFAULTS };
