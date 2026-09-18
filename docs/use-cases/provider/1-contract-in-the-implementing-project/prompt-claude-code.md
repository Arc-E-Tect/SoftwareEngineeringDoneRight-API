---
description: Set up a versioned, file-based API contract in the Gradle project that implements the API, test first.
---

# Set up this project's API contract, test first

Help me set up this Gradle project so that it builds, publishes and consumes its own API contract, with the API-Only Publisher and the API-Only Subscriber.
The project implements the API, and the API's description (OpenAPI, and possibly AsyncAPI) lives in this project.

When we are done, `./gradlew check`:

- bundles and lints the contract, and fails on a fragment that nothing references;
- publishes the contract as a versioned archive to a directory inside `build/`;
- fetches that archive with the Subscriber, verifies it against a committed `apionly.lock`, and puts the document on the classpath;
- runs a test that proves the contract, at the version this project implements, reaches the code.

Everything stays on the local file system.
There is no registry and there are no credentials.

## How to work

- Start in plan mode.
  Investigate first, then present a plan that lists every file you will create or change, and wait for my approval before you edit anything.
- Keep a todo list of the steps below, and update it as you finish each one.
- Work test first.
  Create each failing check before the change that makes it pass, run it, and show me the failure.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- Do not commit, push, or change anything outside this project.
  At the end, tell me what to commit.
- Do not invent configuration keys, commands, tasks or options.
  Everything that exists is listed under the facts below.
  If you need something that is not listed, stop and ask me.

## Principles

- **Build only what this project needs.** The Publisher publishes to a `file` channel inside `build/`, and the Subscriber reads it from there.
  Do not add a registry, a `maven` or `npm` channel, credentials, or another project.
- **A version names one set of documents.** Every change to the contract comes with a new version: in the contract's version file, in `apiContractVersion`, and in `apionly.lock`.
- **Nothing generated goes under `src/`.** Everything the Publisher and the Subscriber write is under `build/`.
- **Never edit a fetched document** under `build/api-spec/`, and never edit `apionly.lock` by hand.
- **If another project in this repository, or a build in another repository, needs this contract, stop and tell me.** That calls for a different setup.

## Versions

Wherever one of these names appears below, in braces, it stands for this version:

| Name | Version |
|---|---|
| `{api-only-publisher-version}` | `0.4.0` |
| `{api-only-subscriber-version}` | `0.3.0` |

These are the versions this prompt was verified with. Use them unless I name newer ones; a newer release works the same unless its changelog says otherwise.

## Facts: API-Only Publisher {api-only-publisher-version}

- npm package `@arc-e-tect/api-only-publisher`, pinned exactly to `{api-only-publisher-version}`.
  It needs Node.js `^22.14.0`, `^24.10.0` or `>=26.0.0`, and npm.
- Install it as an exact dev dependency with an npm script:
  - `package.json` contains `"scripts": { "apionly": "api-only-publisher" }`;
  - install with `npm install --save-dev --save-exact @arc-e-tect/api-only-publisher@{api-only-publisher-version}`;
  - commit `package-lock.json`, and ignore `node_modules/`.
- Run it **only** as `npm run apionly -- <command>`, and as `npm run --silent apionly -- <command>` from Gradle or whenever the output is captured.
  **Never run `npx api-only-publisher`**: that unscoped name is not this package.
- `apionly.yaml`, beside `build.gradle`, with `schemaVersion: 1`.
  These are its keys:
  - `sources.root`, `sources.openapi`, `sources.asyncapi`: where the fragments are.
    A bundle path is relative to `<sources.root>/<sources.openapi>` (or `sources.asyncapi`).
  - `defaults.openapi.lint`: the Redocly configuration file, relative to `apionly.yaml`.
  - `defaults.openapi.outputName`, `defaults.asyncapi.outputName`: keep `openapi.yaml` and `asyncapi.yaml`, because the Subscriber looks for exactly those names.
  - `defaults.openapi.fragmentPaths`: leave it out; the Publisher then records on every OpenAPI component the fragment it came from, as `x-fragment-path`.
  - `defaults.placeholders.strict`.
  - `build.staging`, `build.dist`: working and output directories.
  - `reports.lint`: where lint reports go.
  - `lint.unreferenced`: `error` (the default), `warn` or `off`.
  - `toolchain.redocly: "@redocly/cli@2.52.0"`, `toolchain.asyncapi: "@asyncapi/cli@6.0.2"`.
  - `channels.file.directory`, `channels.file.clean`.
  - `targets.<name>.openapi.bundle`, `targets.<name>.asyncapi.bundle`, `targets.<name>.publish`, `targets.<name>.versionFile`.
- **The version file.** Each published target's version is in `<target>.bundle.properties`, beside its first bundle root, as `version=1.0.0`.
  It must be a semantic release version; a pre-release is cut with `--pre-release <ids>` instead.
  The build stamps it into `info.version`, so the bundle root must have an `info.version`, conventionally `0.0.0`.
- Commands:
  - `build [--target <t>]`: stage, substitute `{{placeholders}}`, bundle, stamp the version, and lint.
  - `lint [--target <t>]`: lint every built document and write a report per document.
    Without `--target`, it also fails on any YAML file under `sources.root` that no target references.
    Lint tools' configuration files, such as `.redocly.yaml`, and the lint configuration `apionly.yaml` names are not fragments, and are skipped wherever they sit.
  - `publish --target <t> --channel file`: write `<directory>/<t>/<version>/<t>-<version>.tgz` and `manifest.json`.
    With `clean: true`, the target's other versions are removed first.
  - `targets`, and `changed --since <git ref>`.
- `{{token}}` in a fragment is replaced by the contents of `<token>.md`, found under the source root.
  An unresolved token fails the build.

## Facts: API-Only Subscriber {api-only-subscriber-version}

- Gradle plugin `id 'com.arc-e-tect.api-only-subscriber' version '{api-only-subscriber-version}'`, from the Gradle Plugin Portal.
  It needs Gradle 8 or newer and Java 21 or newer, and supports the configuration cache.
- Configuration:

  ```groovy
  apiOnlySubscriber {
      channel {
          type = 'file'                                  // 'file' or 'maven'
          directory = file('build/api-only/publish').path
      }
      subscribe('<target>')                              // at apiContractVersion
  }
  ```

- **The version a subscription fetches** is its own `version` if set; otherwise `apiOnlySubscriber.version`; otherwise the project property `apiContractVersion`, from `gradle.properties`, `-P` or `ORG_GRADLE_PROJECT_apiContractVersion`.
  A pre-release is refused unless the subscription sets `allowPrerelease = true`.
- Tasks: `fetchApiSpec<Target>` and `verifyApiSpec<Target>`, with the target in camel case (`orders` gives `fetchApiSpecOrders`), plus the aggregates `fetchApiSpec` and `verifyApiSpec`.
  `check` depends on `verifyApiSpec`, and `processResources` depends on the fetch.
  `verifyApiSpec` deliberately does not depend on the fetch.
- The fetch unpacks `openapi.yaml`, `asyncapi.yaml` where present, and `manifest.json` into `build/api-spec/<target>/`, which is added as a resources directory, so the documents sit at the classpath root.
- `apionly.lock`, beside `build.gradle`, records target, version, channel and a SHA-256 per document.
  It is generated and committed.
- What the Subscriber prints:
  - `Subscribed to <target> <version>` on the first fetch, and `Updated <target> from <old> to <new>` when the version changes;
  - `the published contract for '<target>' <version> is not the one recorded in apionly.lock: ... <version> was published to the file channel again, with different content.` when the documents changed under the same version;
  - `no archive for '<target>' <version> at <path>. Published versions of '<target>': <versions>.` when nothing is published at that version;
  - `the contract for '<target>' has drifted from apionly.lock:` from `verifyApiSpec`, when a fetched document was edited.

## Step 1: Investigate and propose

Do not edit anything in this step.
Find out and report:

1. The Gradle version, the DSL (Groovy or Kotlin), whether the `java` plugin is applied, whether the configuration cache is on, and the test framework the project uses.
2. Where the API description is today: one file or fragments, OpenAPI and/or AsyncAPI, and whether it uses `{{placeholders}}`.
3. The target name, normally the API's name, and the starting version: `1.0.0` unless something says otherwise.
4. Whether a `package.json` exists, and which Node.js and npm versions are installed.
5. What in the project reads the API description today, and how: a path, the classpath, or a generator.
6. The CI system.

Then present the plan and wait for my approval.

## Step 2: The failing contract test

1. Add `apiContractVersion=<version>` to `gradle.properties`, with a comment saying it is the version of the contract this project implements.
2. Make sure the test task can run JUnit tests and passes the property to the test JVM:

   ```groovy
   tasks.named('test') {
       useJUnitPlatform()
       // The contract version this project implements, for ApiContractTest.
       systemProperty 'apiContractVersion', providers.gradleProperty('apiContractVersion').get()
   }
   ```

3. Create the test, in the project's test package:

   ```java
   class ApiContractTest {

       @Test
       void theContractThisProjectImplementsIsOnTheClasspath() throws IOException {
           try (InputStream document = getClass().getResourceAsStream("/openapi.yaml")) {
               assertNotNull(document, "openapi.yaml is not on the test classpath");
               String text = new String(document.readAllBytes(), StandardCharsets.UTF_8);
               String version = System.getProperty("apiContractVersion");
               assertTrue(text.contains("version: " + version),
                   "openapi.yaml does not declare version " + version);
           }
       }
   }
   ```

   If `openapi.yaml` is already on the classpath, from `src/main/resources`, tell me before going on: that copy has to go in step 4.
4. Run `./gradlew test`, and show me that `ApiContractTest` fails.

## Step 3: The contract

1. Install the Publisher, as in the facts, and ignore `node_modules/` and `build/`.
2. Put the fragments under `src/main/api/openapi/`, with one bundle root in `bundles/<target>.yaml`.
   An existing single document can become the bundle root unchanged.
   Make sure its `info` block has a `version`.
3. Create `.redocly.yaml` at the project root, with `extends: [recommended]` unless the project already has lint rules.
4. Create `src/main/api/openapi/bundles/<target>.bundle.properties` with `version=<the version from step 2>`, and a comment saying major is for breaking, minor for additive and patch for anything else.
5. Create `apionly.yaml`:

   ```yaml
   schemaVersion: 1

   sources:
     root: src/main/api
     openapi: openapi

   defaults:
     openapi:
       lint: .redocly.yaml
       outputName: openapi.yaml

   build:
     staging: build/api-only/staging
     dist: build/api-only/dist

   reports:
     lint: build/api-only/reports/lint

   toolchain:
     redocly: "@redocly/cli@2.52.0"

   channels:
     file:
       directory: build/api-only/publish
       clean: true

   targets:
     <target>:
       openapi:
         bundle: bundles/<target>.yaml
   ```

6. Run `npm run apionly -- build` and `npm run apionly -- lint`, and show that both succeed.
   Fix lint errors in the fragments, not in the rules, unless I agree otherwise.

## Step 4: The wiring

1. Add this to `build.gradle`, with `<target>` and `<Target>` replaced, and keep the comments:

   ```groovy
   def npm = System.getProperty('os.name').toLowerCase().contains('windows') ? 'npm.cmd' : 'npm'

   def installApiOnlyPublisher = tasks.register('installApiOnlyPublisher', Exec) {
       group = 'api-only'
       description = 'Installs the API-Only Publisher at the version package-lock.json pins.'
       inputs.files('package.json', 'package-lock.json').withPropertyName('manifests')
       outputs.file('node_modules/.package-lock.json').withPropertyName('installation')
       commandLine npm, 'ci', '--no-audit', '--no-fund'
   }

   def buildApiContract = tasks.register('buildApiContract', Exec) {
       group = 'api-only'
       description = 'Bundles, stamps and lints the <target> contract.'
       dependsOn installApiOnlyPublisher
       inputs.dir('src/main/api').withPropertyName('specification')
       inputs.files('apionly.yaml', 'package-lock.json').withPropertyName('configuration')
       outputs.dir('build/api-only/dist/<target>').withPropertyName('bundle')
       commandLine npm, 'run', '--silent', 'apionly', '--', 'build', '--target', '<target>'
   }

   def publishApiContract = tasks.register('publishApiContract', Exec) {
       group = 'api-only'
       description = 'Publishes the <target> contract to the file channel in build/api-only/publish.'
       dependsOn buildApiContract
       inputs.dir('build/api-only/dist/<target>').withPropertyName('bundle')
       outputs.dir('build/api-only/publish/<target>').withPropertyName('archive')
       commandLine npm, 'run', '--silent', 'apionly', '--', 'publish', '--target', '<target>', '--channel', 'file'
   }

   def lintApiContract = tasks.register('lintApiContract', Exec) {
       group = 'verification'
       description = 'Lints the contract, and fails on a fragment nothing references.'
       dependsOn buildApiContract
       inputs.dir('src/main/api').withPropertyName('specification')
       inputs.dir('build/api-only/dist').withPropertyName('bundles')
       outputs.dir('build/api-only/reports/lint').withPropertyName('reports')
       commandLine npm, 'run', '--silent', 'apionly', '--', 'lint'
   }
   tasks.named('check') { dependsOn lintApiContract }

   apiOnlySubscriber {
       channel {
           type = 'file'
           directory = file('build/api-only/publish').path
       }
       // At the version apiContractVersion in gradle.properties declares.
       subscribe('<target>')
   }

   // The fetch reads what publishApiContract writes.
   tasks.named('fetchApiSpec<Target>') { dependsOn publishApiContract }
   ```

   For the Kotlin DSL, translate it faithfully.
2. Point everything that read the description in step 1 at the fetched document instead: the classpath resource `/openapi.yaml`, or `apiOnlySubscriber.subscription('<target>').openapi` for a Gradle task, never a path string.
   Remove any committed copy of the document.
3. Run `./gradlew check`.
   Show that it prints `Subscribed to <target> <version>`, that `ApiContractTest` passes, and that the build succeeds.
4. Run `./gradlew check` again, and show that the contract tasks are `UP-TO-DATE`.
   If the project uses the configuration cache, run with `--configuration-cache`.
5. Show `apionly.lock`.

## Step 5: Prove each guard, then undo it

Trigger each guard once, show me its message, and restore the project before the next one.

1. **An unreferenced fragment**: add an unreferenced YAML file under `src/main/api/openapi/components/`, run `./gradlew check`, show that `lintApiContract` fails naming the file, then delete it.
2. **A changed contract under an unchanged version**: change a `summary` in a fragment, run `./gradlew check`, show the `is not the one recorded in apionly.lock` refusal, then revert the change and run `./gradlew check` until it passes.
3. **A version change done properly**: make the same change, raise both the version file and `apiContractVersion` to the next minor version, run `./gradlew check`, and show `Updated <target> from ... to ...`.
   Then revert the fragment, both versions and `apionly.lock`, and run `./gradlew check` until it passes.
4. **A hand-edited fetched document**: append a comment to `build/api-spec/<target>/openapi.yaml`, run `./gradlew verifyApiSpec` on its own, show the `has drifted from apionly.lock` failure, then run `./gradlew fetchApiSpec` to restore the document.
   Tell me that `./gradlew check` would have restored the document silently, because it fetches again before verifying.

## Step 6: CI

Ask before doing this step.
Add a CI job that sets up Node.js 24 and Java 21, runs `./gradlew check`, fails when `git diff --exit-code -- apionly.lock` shows a change, and keeps `build/api-only/reports/lint/` as a build artifact.
For GitHub Actions, use `actions/checkout@v7`, `actions/setup-node@v6`, `actions/setup-java@v5`, `gradle/actions/setup-gradle@v6` and `actions/upload-artifact@v7`.

## Hand over

1. Summarise what changed, and list the files to commit: `package.json`, `package-lock.json`, `apionly.yaml`, `.redocly.yaml`, `src/main/api/`, `gradle.properties`, `build.gradle`, `apionly.lock`, the test, `.gitignore`, and the CI file if one was added.
2. Offer to add a section like this to `CLAUDE.md`, and write it only if I agree:

   ```markdown
   ## API contract

   - The API contract is authored in `src/main/api/` and built by the Gradle build with the API-Only Publisher. Edit the fragments, never anything under `build/`.
   - Run the Publisher only as `npm run apionly -- <command>`, never `npx api-only-publisher`.
   - A change to the contract raises `version` in `src/main/api/openapi/bundles/<target>.bundle.properties` and `apiContractVersion` in `gradle.properties` to the same new version, in the same commit as the updated `apionly.lock`: major for breaking, minor for additive, patch for anything else.
   - Every YAML file under `src/main/api/` must be referenced by the contract, or `./gradlew check` fails.
   - Never edit `apionly.lock` or `build/api-spec/` by hand.
   ```
