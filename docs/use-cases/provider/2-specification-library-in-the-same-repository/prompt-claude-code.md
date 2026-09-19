---
description: Set up a versioned, file-based specification library for the projects in this repository, test first.
---

# Set up a specification library for this repository's projects, test first

Help me set up this repository so that one library of API description fragments serves the projects that implement the contracts built from it, with the API-Only Publisher and the API-Only Subscriber.
Each contract has its own version, and each project declares the version of the contract it implements.

When we are done, `./gradlew check` at the root of the Gradle build:

- builds each project's contract from the library, and publishes it as a versioned archive to a directory inside the library's `build/` directory;
- fetches each project's contract with the Subscriber, verifies it against that project's committed `apionly.lock`, and puts it on the project's classpath;
- lints every contract, and fails on a fragment that no contract references;
- runs a test in each project that proves its contract, at the version it implements, reaches the code.

Everything stays on the local file system.
There is no registry, there are no credentials, and nothing generated is committed.

## How to work

- Start in plan mode.
  Investigate first, then present a plan that lists every file you will create or change, and wait for my approval before you edit anything.
- Keep a todo list of the steps below, and update it as you finish each one.
- Work test first.
  Create each failing check before the change that makes it pass, run it, and show me the failure.
- Set up the library and **one** project first, verify them, and stop for my review before wiring the other projects.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- Do not commit or push.
  At the end, tell me what to commit.
- Do not invent configuration keys, commands, tasks or options.
  Everything that exists is listed under the facts below.
  If you need something that is not listed, stop and ask me.

## Principles

- **Build only what this repository needs.** The Publisher publishes to a `file` channel inside the library's `build/` directory, and each project's Subscriber reads it from there.
  Do not add a registry, a `maven` or `npm` channel, or credentials.
- **Nothing generated is committed.** Contracts are built from the fragments in every build; the committed lockfiles are what keep that safe.
- **A version names one set of documents.** Every change to a contract comes with a new version: in its version file, in the implementing project's `apiContractVersion`, and in that project's `apionly.lock`.
  A change to a shared fragment changes every contract that reaches it.
- **Never edit a fetched document** under a project's `build/api-spec/`, and never edit an `apionly.lock` by hand.
- **If a build outside this repository needs a contract, stop and tell me.** That calls for a registry, which is a different setup.

## Versions

Wherever one of these names appears below, in braces, it stands for this version:

| Name | Version |
|---|---|
| `{api-only-publisher-version}` | `0.7.0` |
| `{api-only-subscriber-version}` | `0.3.4` |

These are the versions this prompt was verified with. Use them unless I name newer ones; a newer release works the same unless its changelog says otherwise.

## Facts: API-Only Publisher {api-only-publisher-version}

- npm package `@arc-e-tect/api-only-publisher`, pinned exactly to `{api-only-publisher-version}`.
  It needs Node.js `^22.14.0`, `^24.10.0` or `>=26.0.0`, and npm.
- Install it in the library directory as an exact dev dependency with an npm script:
  - the library's `package.json` contains `"scripts": { "apionly": "api-only-publisher" }`;
  - install with `npm install --save-dev --save-exact @arc-e-tect/api-only-publisher@{api-only-publisher-version}`;
  - commit `package-lock.json`, and ignore `node_modules/`.
- Run it **only** as `npm run apionly -- <command>` in the library directory, and as `npm run --silent apionly -- <command>` from Gradle or whenever the output is captured.
  **Never run `npx api-only-publisher`**: that unscoped name is not this package.
- `apionly.yaml`, in the library directory, with `schemaVersion: 1`.
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
- **Version files.** Each published target's version is in `<target>.bundle.properties`, beside its first bundle root, as `version=1.0.0`.
  It must be a semantic release version; a pre-release is cut with `--pre-release <ids>` instead.
  The build stamps it into `info.version`, so each bundle root must have an `info.version`, conventionally `0.0.0`.
  A `publish: false` target needs no version file.
- Commands:
  - `build [--target <t>]`: stage, substitute `{{placeholders}}`, bundle, stamp the version, and lint.
    Every run rebuilds the same staging directory, so two runs must never overlap.
  - `lint [--target <t>]`: lint every built document and write a report per document.
    Without `--target`, it also fails on any YAML file under `sources.root` that no target references, and it needs every target built first.
    Lint tools' configuration files, such as `.redocly.yaml`, and the lint configuration `apionly.yaml` names are not fragments, and are skipped wherever they sit.
  - `publish --target <t> --channel file`: write `<directory>/<t>/<version>/<t>-<version>.tgz` and `manifest.json`.
    With `clean: true`, the target's other versions are removed first.
  - `changed --since <git ref> [--quiet]`: list the targets whose fragments changed since that ref, one per line with `--quiet`.
  - `targets`.
- `{{token}}` in a fragment is replaced by the contents of `<token>.md`, found under the source root.
  An unresolved token fails the build.

## Facts: API-Only Subscriber {api-only-subscriber-version}

- Gradle plugin `com.arc-e-tect.api-only-subscriber`, version `{api-only-subscriber-version}`, from the Gradle Plugin Portal.
  It needs Gradle 8 or newer and Java 21 or newer, and supports the configuration cache.
- In each project:

  ```groovy
  apiOnlySubscriber {
      channel {
          type = 'file'                                            // 'file' or 'maven'
          directory = new File(contracts, 'build/publish').path    // the library's file channel
      }
      subscribe(target)                                            // at apiContractVersion
  }
  ```

- **The version a subscription fetches** is `-PapiContractVersion` on the command line, which overrides every other; otherwise the subscription's own `apiContractVersion`; otherwise `apiOnlySubscriber.apiContractVersion`; otherwise the project property `apiContractVersion`, which a subproject can set in its **own** `gradle.properties`.
  The old name `version` fails the build and names `apiContractVersion`.
  A pre-release is refused unless the subscription sets `allowPrerelease = true`.
- Tasks, per project: `fetchApiSpec<Target>` and `verifyApiSpec<Target>`, with the target in PascalCase, plus the aggregates `fetchApiSpec` and `verifyApiSpec`.
  `check` depends on `verifyApiSpec`, and `processResources` depends on the fetch.
  The fetch task class, for wiring, is `com.arc_e_tect.gradle.apionly.subscriber.FetchApiSpecTask`.
- The fetch unpacks the documents and `manifest.json` into the project's `build/api-spec/<target>/`, which is added as a resources directory, so the documents sit at the classpath root.
  **A project subscribes to one contract**, the one it is named after.
  Never add a second subscription to a project: a second contract gets a project of its own.
- `apionly.lock`, beside each project's build file, records target, version, channel and a SHA-256 per document.
  It is generated and committed.
- What the Subscriber prints:
  - `Subscribed to <target> <version>`, and `Updated <target> from <old> to <new>`;
  - `the published contract for '<target>' <version> is not the one recorded in apionly.lock: ... <version> was published to the file channel again, with different content.`;
  - `no archive for '<target>' <version> at <path>. Published versions of '<target>': <versions>.`;
  - `the contract for '<target>' has drifted from apionly.lock:` from `verifyApiSpec`.

## Step 1: Investigate and propose

Do not edit anything in this step.
Find out and report:

1. The Gradle build: where its root is, which projects it includes, the Gradle version, the DSL, whether the configuration cache or `--parallel` is used, and the test framework.
2. Where the API descriptions are today, and whether some fragments are already shared between contracts.
3. Which contract each project implements, the target name for each, and whether each project's name matches its target name.
   If they do not match, propose an `apiContractTarget` property per project instead of the project name.
4. Where the library directory should be (`contracts/` unless there is a reason otherwise), and the starting version of each contract: `1.0.0` unless something says otherwise.
5. Whether a `package.json` exists, and which Node.js and npm versions are installed.
6. What in each project reads its API description today, and how.
7. The CI system.

Then present the plan and wait for my approval.

## Step 2: The failing contract test, in the first project

1. In the first project's `gradle.properties`, add `apiContractVersion=<version>`, with a comment saying it is the version of the contract this project implements.
2. Make sure its test task runs JUnit and passes the property to the test JVM: `systemProperty 'apiContractVersion', project.findProperty('apiContractVersion')`.
3. Create the test in the project's test package:

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

4. Run the project's `test` task, and show me that `ApiContractTest` fails.

## Step 3: The library

1. Create the library directory with its `package.json`, install the Publisher, and ignore `node_modules/` and `build/`.
2. Put the fragments under `<library>/specs/openapi/`: one bundle root per contract in `bundles/<target>.yaml`, and shared fragments under `components/common/`.
   Every bundle root's `info` block must have a `version`.
3. Create `<library>/.redocly.yaml`, beside `apionly.yaml`, with `extends: [recommended]` unless the repository already has lint rules.
4. Create `bundles/<target>.bundle.properties` for every contract, with `version=<starting version>`.
5. Create `<library>/apionly.yaml`:

   ```yaml
   schemaVersion: 1

   sources:
     root: specs
     openapi: openapi

   defaults:
     openapi:
       lint: .redocly.yaml
       outputName: openapi.yaml

   build:
     staging: build/staging
     dist: build/dist

   reports:
     lint: build/reports/lint

   toolchain:
     redocly: "@redocly/cli@2.52.0"

   channels:
     file:
       directory: build/publish
       clean: true

   targets:
     <target>:
       openapi:
         bundle: bundles/<target>.yaml
   ```

6. Run `npm run apionly -- build` and `npm run apionly -- lint` in the library, and show that both succeed.

## Step 4: The wiring, for the first project

1. In the root `build.gradle`, add the shared wiring.
   Adapt `subprojects { }` to apply only to the projects that implement a contract, and keep the comments:

   ```groovy
   import org.gradle.api.services.BuildService
   import org.gradle.api.services.BuildServiceParameters

   plugins {
       id 'base'
       id 'com.arc-e-tect.api-only-subscriber' version '{api-only-subscriber-version}' apply false
   }

   def contracts = file('contracts')
   def npm = System.getProperty('os.name').toLowerCase().contains('windows') ? 'npm.cmd' : 'npm'

   // Every Publisher run rebuilds the same staging tree in contracts/build, so no
   // two runs may overlap, even with --parallel.
   abstract class ApiOnlyPublisherRuns implements BuildService<BuildServiceParameters.None> {}
   def publisherRuns = gradle.sharedServices.registerIfAbsent('apiOnlyPublisherRuns', ApiOnlyPublisherRuns) {
       maxParallelUsages = 1
   }

   def installApiOnlyPublisher = tasks.register('installApiOnlyPublisher', Exec) {
       group = 'api-only'
       description = 'Installs the API-Only Publisher at the version contracts/package-lock.json pins.'
       workingDir contracts
       inputs.files(new File(contracts, 'package.json'), new File(contracts, 'package-lock.json')).withPropertyName('manifests')
       outputs.file(new File(contracts, 'node_modules/.package-lock.json')).withPropertyName('installation')
       commandLine npm, 'ci', '--no-audit', '--no-fund'
   }

   def lintApiContracts = tasks.register('lintApiContracts', Exec) {
       group = 'verification'
       description = 'Lints every contract, and fails on a fragment no contract references.'
       dependsOn subprojects.collect { "${it.path}:buildApiContract" }
       usesService publisherRuns
       workingDir contracts
       inputs.dir(new File(contracts, 'specs')).withPropertyName('specification')
       inputs.dir(new File(contracts, 'build/dist')).withPropertyName('bundles')
       outputs.dir(new File(contracts, 'build/reports/lint')).withPropertyName('reports')
       commandLine npm, 'run', '--silent', 'apionly', '--', 'lint'
   }
   tasks.named('check') { dependsOn lintApiContracts }

   subprojects {
       apply plugin: 'java'
       apply plugin: 'com.arc-e-tect.api-only-subscriber'

       // A project implements the contract that has its name.
       def target = project.name

       def buildApiContract = tasks.register('buildApiContract', Exec) {
           group = 'api-only'
           description = "Bundles, stamps and lints the ${target} contract."
           dependsOn installApiOnlyPublisher
           usesService publisherRuns
           workingDir contracts
           inputs.dir(new File(contracts, 'specs')).withPropertyName('specification')
           inputs.files(new File(contracts, 'apionly.yaml'), new File(contracts, 'package-lock.json')).withPropertyName('configuration')
           outputs.dir(new File(contracts, "build/dist/${target}")).withPropertyName('bundle')
           commandLine npm, 'run', '--silent', 'apionly', '--', 'build', '--target', target
       }

       def publishApiContract = tasks.register('publishApiContract', Exec) {
           group = 'api-only'
           description = "Publishes the ${target} contract to the file channel in contracts/build/publish."
           dependsOn buildApiContract
           usesService publisherRuns
           workingDir contracts
           inputs.dir(new File(contracts, "build/dist/${target}")).withPropertyName('bundle')
           outputs.dir(new File(contracts, "build/publish/${target}")).withPropertyName('archive')
           commandLine npm, 'run', '--silent', 'apionly', '--', 'publish', '--target', target, '--channel', 'file'
       }

       apiOnlySubscriber {
           channel {
               type = 'file'
               directory = new File(contracts, 'build/publish').path
           }
           // At the version apiContractVersion in this project's gradle.properties declares.
           subscribe(target)
       }

       tasks.withType(com.arc_e_tect.gradle.apionly.subscriber.FetchApiSpecTask).configureEach {
           dependsOn publishApiContract
       }
   }
   ```

   Keep each project's existing plugins, repositories and dependencies; merge rather than replace.
   For the Kotlin DSL, translate it faithfully.
2. Point everything in the project that read its description at the fetched document: the classpath resource `/openapi.yaml`, or `apiOnlySubscriber.subscription(target).openapi` for a Gradle task, never a path string.
   Remove any committed copy.
3. Run the project's `check`.
   Show `Subscribed to <target> <version>`, that `ApiContractTest` passes, and that the build succeeds.
4. Run it again, with `--parallel` and, if the build uses it, `--configuration-cache`, and show that the contract tasks are `UP-TO-DATE`.
5. Show the project's `apionly.lock`.

**Stop here and wait for my review before wiring the other projects.**

## Step 5: The other projects

For each remaining project: its `apiContractVersion`, its failing `ApiContractTest` (run it and show the failure), then `./gradlew check`, showing its `Subscribed to` line and the passing test.

## Step 6: Prove each guard, then undo it

Trigger each guard once, show me its message, and restore the repository before the next one.

1. **A change to a shared fragment**: change a shared fragment, run `npm run apionly -- changed --since HEAD --quiet` in the library and show which contracts it reaches, then run `./gradlew check --continue` and show each affected project's `is not the one recorded in apionly.lock` refusal.
2. **The change done properly**: raise each affected contract's version file and each implementing project's `apiContractVersion` to the next patch version, run `./gradlew check`, and show the `Updated` lines.
   Then revert the fragment, every version and every `apionly.lock`, and run `./gradlew check` until it passes.
3. **An unreferenced fragment**: add an unreferenced YAML file under `<library>/specs/openapi/components/common/`, run `./gradlew check`, show that `lintApiContracts` fails naming it, and delete it.
4. **A hand-edited fetched document**: append a comment to one project's `build/api-spec/<target>/openapi.yaml`, run that project's `verifyApiSpec` on its own, show the `has drifted from apionly.lock` failure, and run its `fetchApiSpec` to restore the document.

## Step 7: CI

Ask before doing this step.
Add a CI job that sets up Node.js 24 and Java 21, runs `./gradlew check --parallel`, fails when `git diff --exit-code -- '*/apionly.lock'` shows a change, and keeps `<library>/build/reports/lint/` as a build artifact.
For GitHub Actions, use `actions/checkout@v7`, `actions/setup-node@v6`, `actions/setup-java@v5`, `gradle/actions/setup-gradle@v6` and `actions/upload-artifact@v7`.

## Hand over

1. Summarise what changed, and list the files to commit: the library (without `node_modules/` and `build/`), `settings.gradle`, the root `build.gradle`, each project's `gradle.properties`, `apionly.lock` and `ApiContractTest`, `.gitignore`, and the CI file if one was added.
2. Offer to add a section like this to `CLAUDE.md`, and write it only if I agree:

   ```markdown
   ## API contracts

   - Every API contract is built from the fragments in `contracts/specs/` by the Gradle build, with the API-Only Publisher. Edit the fragments, never anything under a `build/` directory.
   - Run the Publisher only as `npm run apionly -- <command>` in `contracts/`, never `npx api-only-publisher`.
   - A change to a contract raises `version` in `contracts/specs/openapi/bundles/<target>.bundle.properties` and `apiContractVersion` in the implementing project's `gradle.properties` to the same new version, in the same commit as that project's updated `apionly.lock`: major for breaking, minor for additive, patch for anything else.
   - A change to a shared fragment changes every contract that reaches it; `npm run apionly -- changed --since <ref>` in `contracts/` lists them.
   - Every YAML file under `contracts/specs/` must be referenced by a contract, or `./gradlew check` fails.
   - Never edit an `apionly.lock` or a `build/api-spec/` directory by hand.
   ```
