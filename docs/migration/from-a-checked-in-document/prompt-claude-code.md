---
description: Replace a committed API description with one the build generates, in four reviewable phases.
---

# Migrate a checked-in API document to a generated one

Help me replace the API description documents committed in this repository, usually under `src/main/resources`, with documents the build generates with the API-Only Publisher, and consumes through the API-Only Subscriber.

Work in four phases, and **stop at the end of each one for my review**. The migration must never change what a consumer sees without my knowing.

## How to work

- Start in plan mode.
  Investigate first, then present a plan that lists every file you will create or change in the current phase, and wait for my approval before you edit anything.
- Keep a todo list of the phases and their steps, and update it as you finish each one.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- Stop after each phase. Summarise what changed, list the files to commit, and wait for me.
- Do not commit, push, or change anything outside this repository.
- Do not invent configuration keys, commands, tasks or options.
  Everything that exists is listed under the facts below.
  If you need something that is not listed, stop and ask me.

## Principles

- **Phase 1 changes nothing downstream.** It proves the generated document matches the committed one.
  If they differ in content rather than formatting, stop and show me.
  **Never** make the diff empty by editing the source until it matches: the committed document may be the one that is wrong, and consumers have been building against it either way.
- **One consumer per pull request** in phase 3. The repository may stay in a mixed state for as long as it needs to.
- **Migrating and refactoring are separate commits.** Do not extract fragments, introduce placeholders or split a document during the migration; that comes after phase 1, checked against the fixture.
- **A version names one set of documents.** Every change to a contract comes with a new version.
- **Nothing generated goes under `src/`** once phase 3 is done for a project, and nothing generated is committed once phase 4 is done.

## Versions

Wherever one of these names appears below, in braces, it stands for this version:

| Name | Version |
|---|---|
| `{api-only-publisher-version}` | `0.4.1` |
| `{api-only-subscriber-version}` | `0.3.1` |

## Facts: API-Only Publisher {api-only-publisher-version}

- npm package `@arc-e-tect/api-only-publisher`, pinned exactly to `{api-only-publisher-version}`.
  It needs Node.js `^22.14.0`, `^24.10.0` or `>=26.0.0`, and npm.
- Install it as an exact dev dependency with an npm script:
  - `package.json` contains `"scripts": { "apionly": "api-only-publisher" }`;
  - install with `npm install --save-dev --save-exact @arc-e-tect/api-only-publisher@{api-only-publisher-version}`;
  - commit `package-lock.json`, and ignore `node_modules/`.
- Run it **only** as `npm run apionly -- <command>`, and as `npm run --silent apionly -- <command>` whenever the output is captured.
  **Never run `npx api-only-publisher`**: that unscoped name is not this package.
- `apionly.yaml` holds `schemaVersion: 1`, `sources.root` and `sources.openapi` (where the fragments are), `defaults.openapi.lint` and `defaults.openapi.outputName: openapi.yaml`, `build.staging` and `build.dist` inside `build/`, `reports.lint`, `toolchain.redocly`, `channels`, and `targets.<name>.openapi.bundle`.
- A contract's version lives in `<target>.bundle.properties`, beside its bundle root.
  There is no `--version` option: the version is read from that file, and `--pre-release <ids>` appends pre-release identifiers to it.
- `distribution` is a transitional block: `distribution.root` and `distribution.layout` say where each built document is copied, and `{target}` in the layout is the target's name.
  The destination directory must already exist.
  It is deleted in phase 4.
- A bundle root that `$ref`s nothing is a valid contract: one file per target is all the Publisher needs.
- The build stamps `x-fragment-path` on every component that came from a fragment, so splitting a document into fragments changes the document.
  Raise the contract's minor version when that happens.

## Facts: API-Only Subscriber {api-only-subscriber-version}

- Gradle plugin `id 'com.arc-e-tect.api-only-subscriber' version '{api-only-subscriber-version}'`, from the Gradle Plugin Portal.
  It needs Gradle 8 or newer and Java 21 or newer, and supports the configuration cache.
- `apiOnlySubscriber { channel { type = 'file'; directory = ... } subscribe('<target>') }` subscribes to the contract the project implements, at the `apiContractVersion` project property.
  A project that calls an API it does not implement uses `subscribeAsClient('<target>') { version = ... }` instead.
- `fetchApiSpec<Target>` fetches and unpacks into `build/api-spec/<target>/`, records the result in `apionly.lock`, and prints `Subscribed to <target> <version>` or `Updated <target> from <a> to <b>`.
- `verifyApiSpec` checks the fetched documents against `apionly.lock`, and is part of `check`.
- `apionly.lock` is committed and never edited by hand.

## Phase 1: reproduce what exists, byte for byte

Downstream changes: none.

1. Find every committed API description: `git ls-files '*openapi*' '*asyncapi*'`, and show me the list before going on.
   Ask me which ones are in scope, and which layout we are migrating to: the contract lives in the project that implements it, in a library shared inside this repository, or in a library in its own repository.
2. Copy each committed document to `.migration/golden/<target>.yaml`. These are the documents the migration must not change.
3. Create the library: `package.json` with the pinned Publisher and the `apionly` script, a Redocly configuration, and `apionly.yaml`.
   The first version of each contract is its committed document, copied unchanged to `<sources.root>/<sources.openapi>/bundles/<target>.yaml`.
4. Put the version the committed document declares in `<target>.bundle.properties`.
5. Run `npm run apionly -- build`, and `diff -u .migration/golden/<target>.yaml <dist>/<target>/openapi.yaml`.
6. Show me the diff, and classify every difference:
   - an empty `components: {}` the bundler adds, or ordering, quoting and wrapping: formatting, so re-baseline the fixture;
   - resolved `$ref`s: expected, but tell me if the committed document still had them, because consumers were resolving them;
   - a different `info.version`: put the committed version in the version file;
   - a lint failure: the document always had that problem; propose a fix in its own commit;
   - **anything else: stop.** The committed document and its source have drifted. Show me both, and wait.
7. Re-baseline the fixture from the built document, add `.migration/verify-golden.sh` that rebuilds and diffs, make it executable, and run it.
8. Add the fixture check to CI.
9. Stop. Summarise, and list the files to commit.

## Phase 2: generate into the existing location

Downstream changes: the committed document becomes generated. Consumers do not change.

1. Add the `distribution` block to `apionly.yaml`, pointing at the directory each project already reads.
2. Run `npm run apionly -- build`, and show that `git status` reports no change to the committed documents.
   If it reports one, stop: phase 1 was not finished.
3. Delete the script or build step that used to produce or copy the documents, and tell me what it was.
4. Change CI to fail when the build changes a committed document: `git diff --exit-code -- <path>`.
5. Put a comment at the top of each committed document saying it is generated and where from.
6. Stop. Summarise, and list the files to commit.

## Phase 3: switch the consumers, one at a time

Downstream changes: one project per pull request.

Do **one** project, and stop. Start with the one I name, or propose the least important one.

1. Add the channel the layout needs to `apionly.yaml`: a `file` channel inside `build/` when the contract lives in the project that implements it, a `file` channel into the library's directory when it is shared inside this repository, or a `maven` channel when it comes from another repository.
2. Apply the Subscriber to the project, with `subscribe('<target>')` for the contract it implements, or `subscribeAsClient('<target>') { version = ... }` for an API it calls, and `apiContractVersion` in `gradle.properties` for the former.
3. Wire the Gradle tasks: install the Publisher, build, publish and lint the contract, make `fetchApiSpec<Target>` depend on the publish, and make `check` depend on the lint.
4. **Before deleting anything**, run `./gradlew fetchApiSpec` and diff the committed document against `build/api-spec/<target>/openapi.yaml`. It must be empty. Show me.
5. In a single commit: delete the committed document, point everything that read it at the fetched one (the classpath resource, or `apiOnlySubscriber.subscription('<target>').openapi` for a Gradle task, never a path string), and remove this project from the `distribution` layout.
6. Run `./gradlew check`, show that it passes, and show `apionly.lock`.
7. Stop. Summarise, and list the files to commit.

## Phase 4: remove the scaffolding

Downstream changes: none.

1. Confirm no project is left in the `distribution` layout, then delete the block.
2. Delete `.migration/`, and the fixture check from CI.
3. Make sure `build/`, `node_modules/` and any other generated directory are in `.gitignore`.
4. Run `git ls-files '*/src/main/resources/*openapi*' '*/src/main/resources/*asyncapi*'` and show that it prints nothing.
5. Run the full build once more.
6. Stop. Summarise, and list the files to commit.

## Hand over

1. Summarise the migration: which contracts moved, which projects switched, and what each now builds against.
2. Tell me the versioning policy the contracts now need, and ask whether to write it down.
3. Offer to add a section like this to `CLAUDE.md`, and write it only if I agree:

   ```markdown
   ## API contracts

   - Contracts are authored under `<sources.root>` and built with the API-Only Publisher. Edit the fragments, never a generated document.
   - Run the Publisher only as `npm run apionly -- <command>`, never `npx api-only-publisher`.
   - A change to a contract raises `version` in its `<target>.bundle.properties`, and the subscribing project's version, in the same commit as the updated `apionly.lock`.
   - Never edit `apionly.lock` or anything under `build/api-spec/` by hand.
   ```
