# Set up a contracts repository with the API-Only Publisher

Help me set up this repository as a specification library. It holds API descriptions (OpenAPI and/or AsyncAPI) that are implemented or consumed in **other repositories**. It builds one bundled document per target and, from CI, publishes each target that changed to a Maven repository, so Gradle builds elsewhere can subscribe to it with the API-Only Subscriber.

Reference manual (read it if you can reach the web; the facts below are enough if you cannot):
https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/docs/in-its-own-repository.adoc

## Principles

- Publishing exists here because consumers outside this repository need it. Add no channel that nobody consumes: `maven` for Gradle consumers, and `npm` only if I confirm a non-JVM consumer exists.
- Every target is versioned independently. A commit releases only the targets whose closure it changed (`changed --since`).
- The versioning policy (patch/minor/major) is my decision. Propose options; do not pick one silently.
- Credentials come only from the environment. `apionly.yaml` names the variable (`channels.maven.tokenEnv`) and never holds a value.
- Pin everything: the Publisher version and the bundler versions under `toolchain`.

## Facts: API-Only Publisher (do not invent options beyond these)

- npm package `@arc-e-tect/api-only-publisher`, pinned exactly to `0.0.2`. Requires Node 22+ and npm 10+.
- Run it **only** through an npm script: `"scripts": { "apionly": "api-only-publisher" }`, invoked as `npm run apionly -- <command>`. Use `npm run --silent apionly -- ...` whenever the output is captured. **Never run `npx api-only-publisher`**: that unscoped name is not this package, and npx would resolve it against the public registry. The one exception is the one-time scaffold, `npx @arc-e-tect/api-only-publisher@0.0.2 init <dir>`.
- Configuration file `apionly.yaml` (`schemaVersion: 1`): `sources.root`, `sources.openapi`, `sources.asyncapi`; `defaults.openapi.lint`, `defaults.<kind>.outputName`, `defaults.placeholders.strict`; `build.staging`, `build.dist`; `toolchain.redocly` (`"@redocly/cli@2.52.0"`), `toolchain.asyncapi` (`"@asyncapi/cli@6.0.2"`); `targets.<name>.openapi.bundle` / `.asyncapi.bundle` / `.publish`; `channels`.
- `channels.maven`: `groupId` (required), `repository` (a filesystem path writes a Maven layout to disk; an `http(s)` URL deploys with a bearer token read from the environment variable named by `tokenEnv`, which defaults to `MAVEN_TOKEN`), and optionally `artifactId` and `extension` (default `tgz`).
- `channels.file`: `directory` (default `publish`). It writes `<directory>/<target>/<version>/<target>-<version>.tgz`.
- `channels.npm`: `scope`, `access`, `registry`, `provenance`, and `publish: false` to pack without publishing.
- A bundle path is relative to `<sources.root>/<sources.<kind>>`. The built document is `<build.dist>/<target>/<outputName>`.
- Commands: `init`, `build [--target <t>]... [--version <v>]`, `lint`, `targets`, `closure`, `changed --since <ref> [--quiet]`, `pack --version <v>`, `publish --version <v> [--target <t>]... [--channel <c>]...`, `split`.
- `pack`/`publish` refuse a document whose `info.version` differs from `--version`, so always run `build --version v` before `publish --version v`, with the same `v`.
- `changed --since <ref> --quiet` prints one changed target per line and nothing else. It needs git history (`fetch-depth: 0` in GitHub Actions).
- A pre-release (`-rc.1`, `-SNAPSHOT`) is published to npm under the `next` tag and is refused by Subscriber builds unless they opt in.
- A target with `publish: false` is built and linted but never published.

## Step 1: Investigate, then propose (do not edit anything yet)

Report:

1. Whether this repository is empty, already has an `apionly.yaml`, or holds existing API descriptions, and in what layout.
2. The targets: their names, and which have OpenAPI and/or AsyncAPI.
3. Where artifacts should be published: the Maven repository URL and group id. Ask me if you cannot tell.
4. The CI system. This prompt assumes GitHub Actions; if it is something else, say so and adapt.
5. How versions will be decided. Offer two options: Conventional Commits scoped by target name, or a committed file holding one version per target.

Then give me a plan that lists every file you will create or change, and wait for my go-ahead.

## Step 2: Implement

1. If there is no library yet, run `npx @arc-e-tect/api-only-publisher@0.0.2 init .`.
2. Create `package.json` with the pinned devDependency and the `apionly` script, run `npm install`, and commit the lockfile.
3. Write `apionly.yaml`: the targets, pinned `toolchain` versions, `defaults.placeholders.strict: true`, and a `channels.maven` block with `tokenEnv`.
4. Make sure `.gitignore` covers `build/`, `dist/`, `node_modules/` and `publish/`.
5. Add a release workflow that runs on pushes to the main branch and:
   - checks out with full history, sets up Node 22, and runs `npm ci`;
   - lists the changed targets with `npm run --silent apionly -- changed --since <previous commit> --quiet`;
   - for each changed target, determines its version, runs `npm run apionly -- build --version <v> --target <t>` and then `npm run apionly -- publish --version <v> --target <t>`, and tags `<t>-v<v>`;
   - passes the token through the environment variable named in `tokenEnv`.
6. Implement the versioning policy we agreed on, as a script the workflow calls.

## Step 3: Verify, and show me the output

1. `npm run apionly -- targets` and `npm run apionly -- build` succeed for every target.
2. `npm run apionly -- closure` prints a file count and hash for each target.
3. Publish locally, with no remote: `npm run apionly -- build --version 0.0.1-rc.1` followed by `npm run apionly -- publish --version 0.0.1-rc.1 --channel file`, then list the files written under `publish/`.
4. Make a throwaway commit that touches one target's fragment, show that `npm run --silent apionly -- changed --since HEAD~1 --quiet` lists only that target, then drop the commit.
5. If `actionlint` is available, run it on the workflow.

Do not report a step as passing unless you ran it.

## Finally

Summarise the changes. List the repository secrets I need to create, and the exact values consumers need: repository URL, group id, and target names. Then offer to add a short section to `CLAUDE.md` or `AGENTS.md` describing how releases work in this repository. Ask before writing it.
