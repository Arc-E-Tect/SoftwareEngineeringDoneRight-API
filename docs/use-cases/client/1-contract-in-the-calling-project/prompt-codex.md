---
description: Keep the contract of an API this Gradle project calls in the project itself, and build against a verified copy, test first.
---

# Keep the contract of an API this project calls in the project, test first

Help me set up this Gradle project so that it holds the OpenAPI contract of an API it **calls**, and builds against a versioned, verified copy of it, with the API-Only Publisher and the API-Only Subscriber.
The contract's fragments live in this project; nothing is published outside it.

When we are done, `./gradlew check`:

- bundles and lints the contract, and fails on a fragment that nothing references;
- publishes it to a directory inside `build/`, fetches it from there as a client subscription, verifies it against a committed `apionly.lock`, and puts it on the classpath at `/contracts/<target>/openapi.yaml`;
- runs a test that proves the contract, at the version this project calls, reaches the code.

## How to work

- Begin with a read-only investigation.
  Then present a plan that lists every file you will create or change, and do not edit anything until I approve it.
- Keep your plan updated as you finish each step below.
- Work test first.
  Create each failing check before the change that makes it pass, run it, and show me the failure.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- **Network access**: installing packages, running bundlers, and Gradle resolving plugins, libraries and contracts need it.
  If the sandbox blocks network access, ask me to approve those commands with network access, or to run them myself, rather than working around the failure.
- Do not commit or push.
  At the end, tell me what to commit.
- Do not invent configuration keys, commands, tasks or options.
  Everything that exists is listed under the facts below.
  If you need something that is not listed, stop and ask me.

## Principles

- **This project calls the API; it does not implement it.** If it implements the API, stop and tell me: that calls for a different setup.
- **No registry.** The contract never leaves this project, so it is published to a directory in `build/`.
- **The version is in the contract**, in its version file, and this project declares the version it calls in `gradle.properties`; the two change together.
- **Nothing generated lives under `src/`**, and fetched documents are never edited or copied.
- **Pin everything**: the Publisher exactly, and the bundler under `toolchain`.

## Facts: API-Only Publisher 0.3.0

- npm package `@arc-e-tect/api-only-publisher`, pinned exactly to `0.3.0`; it needs Node.js `^22.14.0`, `^24.10.0` or `>=26.0.0`.
- `package.json` contains `"scripts": { "apionly": "api-only-publisher" }`; install with `npm install --save-dev --save-exact @arc-e-tect/api-only-publisher@0.3.0`; ignore `node_modules/` and `build/`.
- Run it **only** as `npm run apionly -- <command>`, or `npm run --silent apionly -- <command>` from Gradle.
  **Never run `npx api-only-publisher`**: that unscoped name is not this package.
- `apionly.yaml`, at the project root:

  ```yaml
  schemaVersion: 1
  sources:
    root: src/main/api
    openapi: openapi
  defaults:
    openapi:
      lint: .redocly.yaml          # beside apionly.yaml
      outputName: openapi.yaml     # the Subscriber looks for exactly this name
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

- The contract's version is in `src/main/api/openapi/bundles/<target>.bundle.properties` as `version=1.0.0`, a release version; the build stamps it into `info.version`, so the bundle root needs `info.version: 0.0.0`.
- `build --target <t>` bundles, stamps and lints; `lint` lints every document and fails on any YAML file under `sources.root` that no target references; `publish --target <t> --channel file` writes `build/api-only/publish/<t>/<version>/<t>-<version>.tgz` with a `manifest.json`.

## Facts: API-Only Subscriber 0.2.0

- Gradle plugin `com.arc-e-tect.api-only-subscriber`, version `0.2.0`; Gradle 8 or newer, Java 21 or newer; supports the configuration cache.
- An API the project calls is declared with `subscribeAsClient`:

  ```groovy
  apiOnlySubscriber {
      channel {
          type = 'file'
          directory = file('build/api-only/publish').path
      }
      subscribeAsClient('<target>') {
          version = providers.gradleProperty('<target>ApiVersion')   // required: no default
          // allowPrerelease = false                                  // the default
      }
  }
  ```

- A client subscription sets its own `version`; it does not read `apiContractVersion`, which is the version of a contract the project implements.
  Without one, the build fails with `client subscription '<target>' declares no version`.
- Tasks: `fetchApiSpec<Target>` and `verifyApiSpec<Target>` (target in camel case), and the aggregates `fetchApiSpec` and `verifyApiSpec`; `check` depends on `verifyApiSpec`, and `processResources` on the fetch.
- The fetch unpacks into `build/api-spec/<target>/`, records the version, the channel and a SHA-256 per document in `apionly.lock`, and, with the `java` plugin, `processResources` copies the documents to `contracts/<target>/` on the classpath.
- A locked version whose bytes changed is refused: `the published contract for '<target>' <version> is not the one recorded in apionly.lock`.
- Subscribing to the same target with `subscribe` and `subscribeAsClient` fails the build.

## The Gradle wiring

Append this to `build.gradle`, with `<target>` filled in:

```groovy
def npm = System.getProperty('os.name').toLowerCase().contains('windows') ? 'npm.cmd' : 'npm'

def installApiOnlyPublisher = tasks.register('installApiOnlyPublisher', Exec) {
    group = 'api-only'
    inputs.files('package.json', 'package-lock.json').withPropertyName('manifests')
    outputs.file('node_modules/.package-lock.json').withPropertyName('installation')
    commandLine npm, 'ci', '--no-audit', '--no-fund'
}
def buildApiContract = tasks.register('buildApiContract', Exec) {
    group = 'api-only'
    dependsOn installApiOnlyPublisher
    inputs.dir('src/main/api').withPropertyName('specification')
    inputs.files('apionly.yaml', 'package-lock.json').withPropertyName('configuration')
    outputs.dir('build/api-only/dist/<target>').withPropertyName('bundle')
    commandLine npm, 'run', '--silent', 'apionly', '--', 'build', '--target', '<target>'
}
def publishApiContract = tasks.register('publishApiContract', Exec) {
    group = 'api-only'
    dependsOn buildApiContract
    inputs.dir('build/api-only/dist/<target>').withPropertyName('bundle')
    outputs.dir('build/api-only/publish/<target>').withPropertyName('archive')
    commandLine npm, 'run', '--silent', 'apionly', '--', 'publish', '--target', '<target>', '--channel', 'file'
}
def lintApiContract = tasks.register('lintApiContract', Exec) {
    group = 'verification'
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
    subscribeAsClient('<target>') {
        version = providers.gradleProperty('<target>ApiVersion')
    }
}
tasks.named('fetchApiSpec<Target>') { dependsOn publishApiContract }
```

## Step 1: Investigate and propose

Read only; do not edit anything in this step.
Find out and report:

1. The Gradle version, the DSL, whether the `java` plugin is applied, whether the configuration cache is on, the test framework, and which Node.js and npm versions are installed.
2. The API this project calls: its name (the target), and whether a description of it exists already, in this project or elsewhere.
3. Whether this project also implements an API; if it does, tell me, because that changes the setup.
4. The CI system.

Then present the plan and wait for my approval.

## Step 2: The contract, building on its own

1. Create `package.json`, install the Publisher, and ignore `node_modules/` and `build/`.
2. Put the fragments under `src/main/api/openapi/`, with the bundle root in `bundles/<target>.yaml`, from the existing description if there is one.
3. Create `.redocly.yaml` at the project root with `extends: [recommended]` unless there are existing rules, the version file with `version=1.0.0`, and `apionly.yaml`.
4. **Prove the lint fails first**: add an unreferenced YAML file under `src/main/api/openapi/components/`, run `npm run apionly -- build` and `npm run apionly -- lint`, show the failure naming the file, delete it, and show both succeed.

## Step 3: The failing contract test

1. Add `<target>ApiVersion=1.0.0` to `gradle.properties`, with a comment saying it is the version of the API this project calls.
2. Apply the Subscriber plugin, and make the test task run JUnit with `systemProperty '<target>ApiVersion', providers.gradleProperty('<target>ApiVersion').get()`.
3. Create a test that reads `/contracts/<target>/openapi.yaml` from the classpath, and asserts that it exists and contains `version: ` followed by that property.
4. Run `./gradlew test`, and show me that it fails.

## Step 4: The wiring, green

1. Append the Gradle wiring above.
2. Run `./gradlew check`; show `Subscribed to <target> 1.0.0`, the passing test, and `apionly.lock`.
3. Run `./gradlew check --configuration-cache` twice, and show the fetch `UP-TO-DATE` the second time.

## Step 5: Prove each guard, then undo it

1. **A changed fragment under an unchanged version**: change a `summary`, run `./gradlew check`, and show the `is not the one recorded in apionly.lock` refusal.
2. **The change done properly**: raise the version file and `<target>ApiVersion` to `1.1.0`, run `./gradlew check`, and show `Updated <target> from 1.0.0 to 1.1.0`.
3. **Only one version raised**: raise only `<target>ApiVersion`, and show the `no archive for` failure; restore it.

## Step 6: CI

Ask before doing this step.
Add a CI job with Node.js 24 and Java 21 that runs `./gradlew check`, fails when `git diff --exit-code -- apionly.lock` shows a change, and keeps `build/api-only/reports/lint/`.
For GitHub Actions, use `actions/checkout@v7`, `actions/setup-node@v6`, `actions/setup-java@v5`, `gradle/actions/setup-gradle@v6` and `actions/upload-artifact@v7`.

## Hand over

1. Summarise what changed, and list the files to commit.
2. Offer to add a section like this to `AGENTS.md`, and write it only if I agree:

   ```markdown
   ## The contract of the API this project calls

   - The contract of the <target> API lives in `src/main/api/`. Run the Publisher only as `npm run apionly -- <command>`.
   - A change to the contract raises `version` in its `.bundle.properties` and `<target>ApiVersion` in `gradle.properties` together, and commits the updated `apionly.lock`.
   - Every YAML file under `src/main/api/` must be referenced by the contract, or the lint fails.
   - Never edit `build/api-spec/` or `apionly.lock` by hand.
   - Installing packages, and Gradle resolving plugins and contracts, need network access.
   ```
