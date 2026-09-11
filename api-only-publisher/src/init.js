"use strict";

// `init` scaffolds a specification repository.
//
// This is how the *conventions* become reusable, as opposed to the code. The
// layout below is the one the library documentation describes; a project that
// starts from it inherits the common/<product> split, the shared info block with
// placeholder snippets, and a configuration that already builds.

const fs = require("fs");
const path = require("path");

const CONFIG = `# apionly.yaml
#
# Declares what this library builds, from where, and where each document goes.

schemaVersion: 1

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
  placeholders:
    # Fail the build on a {{token}} with no matching Markdown file, rather than
    # emitting a marker into a published contract.
    strict: true

build:
  staging: build/staging
  dist: dist

toolchain:
  redocly: "@redocly/cli@2.52.0"
  asyncapi: "@asyncapi/cli@6.0.2"

targets:
  example-service:
    openapi:
      bundle: bundles/example-service_openapi_structure.yaml
`;

const INFO = `title: Example API
version: 0.0.0
description: |
  What this API is for.

  {{conventions}}
contact:
  name: Example Team
  url: https://example.invalid
license:
  name: Apache-2.0
  url: https://www.apache.org/licenses/LICENSE-2.0.html
`;

const CONVENTIONS = `## Conventions

Prose shared by every document in this library lives in a Markdown snippet like
this one, pulled into the specification by a placeholder token. Replace this file
with whatever your own API consumers need to know up front -- pagination, status
codes, error shapes.
`;

const SERVERS = `- url: https://api.example.invalid
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

const PATH_FRAGMENT = `get:
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
              type: string
    '400':
      $ref: '../../components/common/responses/errors/InvalidRequestProblemV1.yaml'
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

const FILES = {
    "apionly.yaml": CONFIG,
    ".redocly.yaml": REDOCLY,
    ".gitignore": GITIGNORE,
    "specs/openapi/shared/info.yaml": INFO,
    "specs/openapi/shared/conventions.md": CONVENTIONS,
    "specs/openapi/shared/servers.yaml": SERVERS,
    "specs/openapi/bundles/example-service_openapi_structure.yaml": BUNDLE,
    "specs/openapi/paths/example/ExamplesV1.yaml": PATH_FRAGMENT,
    "specs/openapi/components/common/security/BearerAuth.yaml": SECURITY_SCHEME,
    "specs/openapi/components/common/responses/errors/InvalidRequestProblemV1.yaml": PROBLEM,
};

function init(targetDir, { force = false, log = () => {} } = {}) {
    const created = [];
    const skipped = [];

    for (const [rel, content] of Object.entries(FILES)) {
        const file = path.join(targetDir, rel);
        if (fs.existsSync(file) && !force) {
            skipped.push(rel);
            continue;
        }
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, content);
        created.push(rel);
    }

    for (const rel of created) log(`  created  ${rel}`);
    for (const rel of skipped) log(`  exists   ${rel} (left alone; --force overwrites)`);
    return { created, skipped };
}

module.exports = { init, FILES };
