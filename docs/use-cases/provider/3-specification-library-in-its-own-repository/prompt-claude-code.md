---
description: Set up this repository as a specification library that releases versioned API contracts to a Maven repository, test first.
---

# Set up this repository to release API contracts, test first

Help me set up this repository as a specification library with the API-Only Publisher.
It holds API descriptions (OpenAPI, and possibly AsyncAPI) that projects in **other repositories** implement or call.
Each contract has its own version, and each version is released once, to a Maven repository, from which those projects subscribe with the API-Only Subscriber.

When we are done:

- every pull request builds and lints every contract, fails on a fragment that no contract references, and fails when a changed contract's version is already released;
- every push to the main branch releases each contract whose version has no release tag yet, and tags it `<target>-v<version>`;
- nothing is released twice, and nothing is released without a new version.

## How to work

- Start in plan mode.
  Investigate first, then present a plan that lists every file you will create or change, and wait for my approval before you edit anything.
- Keep a todo list of the steps below, and update it as you finish each one.
- Work test first.
  Make each check fail on purpose before relying on it, and show me the failure.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- Do not commit, push, create tags on a shared remote, or publish to the real Maven repository.
  Everything you run publishes to a directory inside `build/`.
- **Never ask me for a credential, and never write one into any file.**
- **Never guess the Maven repository URL or the group id.** Ask me.
- Do not invent configuration keys, commands or options.
  Everything that exists is listed under the facts below.
  If you need something that is not listed, stop and ask me.

## Principles

- **A registry is needed here only because the consumers are in other repositories.** Publish to `maven`; add `npm` or a GitHub release only if I confirm a consumer without a JVM exists.
- **The version is in the library.** Each contract's version file says what its next release is; the person who changes the contract raises it, in the same pull request.
- **A version is released once.** Release tags record what was released; the release publishes exactly the versions that have no tag.
- **Credentials come only from the environment.** `apionly.yaml` names the variable, never its value.
- **Pin everything**: the Publisher, and the bundler versions under `toolchain`.

## Facts: API-Only Publisher 0.2.0

- npm package `@arc-e-tect/api-only-publisher`, pinned exactly to `0.2.0`.
  It needs Node.js `^22.14.0`, `^24.10.0` or `>=26.0.0`, and npm.
- Install it as an exact dev dependency with an npm script:
  - `package.json` contains `"scripts": { "apionly": "api-only-publisher" }`;
  - install with `npm install --save-dev --save-exact @arc-e-tect/api-only-publisher@0.2.0`;
  - commit `package-lock.json`, and ignore `node_modules/` and `build/`.
- Run it **only** as `npm run apionly -- <command>`, and as `npm run --silent apionly -- <command>` whenever the output is captured.
  **Never run `npx api-only-publisher`**: that unscoped name is not this package.
- `apionly.yaml`, at the repository root, with `schemaVersion: 1`.
  These are its keys:
  - `sources.root`, `sources.openapi`, `sources.asyncapi`: where the fragments are.
    A bundle path is relative to `<sources.root>/<sources.openapi>` (or `sources.asyncapi`).
  - `defaults.openapi.lint`: the Redocly configuration file, relative to `apionly.yaml`.
  - `defaults.openapi.outputName`, `defaults.asyncapi.outputName`: keep `openapi.yaml` and `asyncapi.yaml`, because the Subscriber looks for exactly those names.
  - `defaults.placeholders.strict`.
  - `build.staging`, `build.dist`, `reports.lint`.
  - `lint.unreferenced`: `error` (the default), `warn` or `off`.
  - `toolchain.redocly: "@redocly/cli@2.52.0"`, `toolchain.asyncapi: "@asyncapi/cli@6.0.2"`.
  - `channels.maven`: `groupId` (required); `repository`, where a directory path writes a Maven repository layout to disk and an `http(s)` URL uploads with an HTTP `PUT` per file, sending `Authorization: Bearer <token>` with the token read from the environment variable `tokenEnv` names (default `MAVEN_TOKEN`); optionally `artifactId` and `extension` (default `tgz`).
  - `channels.file`: `directory`, `clean`.
  - `channels.npm` and `channels.github-release` exist too, for consumers without a JVM.
  - `targets.<name>.openapi.bundle`, `targets.<name>.asyncapi.bundle`, `targets.<name>.publish`, `targets.<name>.versionFile`.
- **Version files.** Each published target's version is in `<target>.bundle.properties`, beside its first bundle root, as `version=1.0.0`.
  It must be a semantic release version; a pre-release is cut with `--pre-release <ids>` on `build` and `publish`.
  The build stamps it into `info.version`, so each bundle root needs an `info.version`, conventionally `0.0.0`.
- Commands:
  - `build [--target <t>] [--pre-release <ids>]`: stage, substitute `{{placeholders}}`, bundle, stamp the version, and lint.
  - `lint`: lint every built document, write a report per document, and fail on any YAML file under `sources.root` that no target references.
    **So the Redocly configuration must live outside `sources.root`.**
  - `publish [--target <t>] [--pre-release <ids>] [--channel <c>]`: pack each built target with a `manifest.json` and ship the same bytes to every configured channel, or only to `--channel`.
    To a Maven repository it writes `<groupId path>/<artifactId>/<version>/<artifactId>-<version>.tgz`, `.pom` and `-manifest.json`.
  - `changed --since <git ref> --quiet`: the targets whose fragments changed since that ref, one per line.
  - `targets`: one line per target, `<name>  [<kinds>]`, with `(publish: false)` where it applies.
- A pre-release goes to npm under the `next` dist-tag, and a consumer's Subscriber refuses it unless the subscription sets `allowPrerelease = true`.

## The two scripts

These are exact; adapt only the version file path if the layout differs.

`scripts/check-versions.sh`:

```bash
#!/usr/bin/env bash
# Fails when a contract changed since <ref> but its version is already released.
set -euo pipefail
since="$1"

status=0
for target in $(npm run --silent apionly -- changed --since "$since" --quiet); do
  version="$(sed -n 's/^version=//p' "specs/openapi/bundles/$target.bundle.properties")"
  if git rev-parse --quiet --verify "refs/tags/$target-v$version" > /dev/null; then
    echo "::error title=Contract version not raised::$target changed, but $target $version is already released. Raise its version."
    status=1
  fi
done
exit "$status"
```

`scripts/release.sh`:

```bash
#!/usr/bin/env bash
# Publishes every contract whose current version has no release tag yet, and tags it.
# Run after `npm run apionly -- build`.
set -euo pipefail

for target in $(npm run --silent apionly -- targets | grep -v '(publish: false)' | awk '{print $1}'); do
  version="$(sed -n 's/^version=//p' "specs/openapi/bundles/$target.bundle.properties")"
  tag="$target-v$version"
  if git rev-parse --quiet --verify "refs/tags/$tag" > /dev/null; then
    continue
  fi
  npm run --silent apionly -- publish --target "$target"
  git tag "$tag"
  echo "Released $target $version"
done
```

## Step 1: Investigate and propose

Do not edit anything in this step.
Find out and report:

1. Whether this repository is empty, already has an `apionly.yaml`, or holds existing API descriptions, and in what layout.
2. The contracts: their target names, whether each has OpenAPI and/or AsyncAPI, shared fragments, and each one's starting version (`1.0.0` unless something says otherwise).
3. Whether any tags already exist that could look like `<target>-v<version>`.
4. The Maven repository URL and group id to release to.
   Ask me if you cannot tell.
5. Which Node.js and npm versions are installed, and the CI system.

Then present the plan and wait for my approval.

## Step 2: The library

1. Create `package.json`, install the Publisher, and ignore `node_modules/` and `build/`.
2. Put the fragments under `specs/openapi/`: one bundle root per contract in `bundles/<target>.yaml`, shared fragments under `components/common/`.
   Every bundle root's `info` block needs a `version`.
3. Create `.redocly.yaml` at the root, **not** under `specs/`, with `extends: [recommended]` unless there are existing rules.
4. Create `specs/openapi/bundles/<target>.bundle.properties` for every contract.
5. Create `apionly.yaml` with the targets, the pinned `toolchain`, and, **for now**, `channels.maven` with the agreed `groupId` and `repository: build/maven-repository`.

## Step 3: The checks, each failing first

1. **The lint.** Add an unreferenced YAML file under `specs/openapi/components/common/`, run `npm run apionly -- build` and `npm run apionly -- lint`, and show that `lint` fails naming the file.
   Delete the file, run both again, and show that they succeed.
2. **Publishing, locally.** Run `npm run apionly -- publish`, and list the files written under `build/maven-repository/`.
   Then delete `build/`.
3. **The scripts.** Create `scripts/check-versions.sh` and `scripts/release.sh` exactly as above, make them executable, and commit everything locally, **without pushing**.
4. **The first release, locally.** Run `npm run apionly -- build` and `./scripts/release.sh`.
   Show that every contract is released and tagged.
   Run `./scripts/release.sh` again, and show that it releases nothing.
5. **A change without a new version.** Change a fragment of one contract, commit locally, run `./scripts/check-versions.sh HEAD~1`, and show the `Contract version not raised` error.
6. **The change done properly.** Raise that contract's version, commit locally, run `./scripts/check-versions.sh HEAD~2`, and show that it passes.
   Run `npm run apionly -- build` and `./scripts/release.sh`, and show that only that contract is released.
7. **Clean up.** Delete every tag you created, reset the local commits from steps 5 and 6 with my approval, and delete `build/`.

## Step 4: The real repository and CI

Ask before doing this step.

1. Change `channels.maven.repository` to the agreed URL, and add `tokenEnv: MAVEN_TOKEN`.
2. Tell me to check, once, that the repository accepts an upload with a bearer token, by publishing a throwaway version from my own shell; do not do it yourself.
3. Add a pull-request workflow: check out with full history (`fetch-depth: 0`), set up Node.js 24, `npm ci`, `npm run apionly -- build`, `npm run apionly -- lint`, `./scripts/check-versions.sh "origin/$GITHUB_BASE_REF"`, and keep `build/reports/lint/` as an artifact.
4. Add a release workflow on pushes to the main branch, with `contents: write`: check out with full history, set up Node.js 24, `npm ci`, build, lint, `./scripts/release.sh` with `MAVEN_TOKEN` from a repository secret, and `git push origin --tags`.
5. For GitHub Actions, use `actions/checkout@v7`, `actions/setup-node@v6` and `actions/upload-artifact@v7`, and run `actionlint` on both workflows if it is available.

## Hand over

1. Summarise what changed, and list the files to commit.
2. List the repository secret to create, by name: `MAVEN_TOKEN`.
3. List what every consuming project needs, exactly: the Maven repository URL, the group id, each target name, and the first released versions.
   Tell me that each consuming project can be set up with the provider use case 4 prompt, "Implement a published API contract in this project, test first", and each project that calls them with the client use case 3 prompt, "Call APIs from a specification library in another repository, test first".
4. Offer to add a section like this to `CLAUDE.md`, and write it only if I agree:

   ```markdown
   ## Releasing API contracts

   - The contracts are built from `specs/` with the API-Only Publisher. Run it only as `npm run apionly -- <command>`, never `npx api-only-publisher`.
   - A change to a contract raises `version` in `specs/openapi/bundles/<target>.bundle.properties`, in the same pull request: major for breaking, minor for additive, patch for anything else. `scripts/check-versions.sh` fails a pull request that forgets.
   - Every YAML file under `specs/` must be referenced by a contract, or the lint fails.
   - Merging to the main branch releases every contract whose version has no `<target>-v<version>` tag. Never delete or move a release tag, and never republish a released version.
   ```
