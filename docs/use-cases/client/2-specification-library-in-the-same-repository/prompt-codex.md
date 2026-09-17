---
description: Make a project in this repository call APIs whose contracts are in a specification library in the same repository, test first.
---

# Call APIs from a specification library in this repository, test first

Help me set up this repository so that a project in it builds against the contracts of the APIs it **calls**, with the API-Only Publisher and the API-Only Subscriber.
The contracts' fragments are in a specification library in this repository, in `contracts/`, or will be.
The calling project does not implement these APIs.

When we are done, `./gradlew check`:

- builds, publishes and lints every contract in the library, and fails on a fragment that no contract references;
- in the calling project, fetches each API it calls as a client subscription at the version the project declares, verifies it against a committed `apionly.lock`, and puts it on the classpath at `/contracts/<target>/openapi.yaml`;
- runs a test in the calling project that proves each contract reaches the code.

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

- **The calling project calls these APIs.** If it implements one of them, stop and tell me: that contract is subscribed with `subscribe`, not `subscribeAsClient`.
- **No registry.** The contracts are published to a directory in `contracts/build/`.
- **Each API has its own version**, in the calling project's `gradle.properties`, and the contract's version file and that property change together.
- **Nothing generated is committed**, and fetched documents are never edited or copied.
- **Pin everything**: the Publisher exactly, and the bundler under `toolchain`.

## Versions

Wherever one of these names appears below, in braces, it stands for this version:

| Name | Version |
|---|---|
| `{api-only-publisher-version}` | `0.4.0` |
| `{api-only-subscriber-version}` | `0.3.0` |

## Facts: API-Only Publisher {api-only-publisher-version}

- npm package `@arc-e-tect/api-only-publisher`, pinned exactly to `{api-only-publisher-version}`, installed in `contracts/` with `npm install --save-dev --save-exact @arc-e-tect/api-only-publisher@{api-only-publisher-version}`, with `"scripts": { "apionly": "api-only-publisher" }` in `contracts/package.json`.
- Run it only as `npm run apionly -- <command>` in `contracts/`; never `npx api-only-publisher`.
- `contracts/apionly.yaml`: `sources.root: specs`, `sources.openapi: openapi`, `defaults.openapi.lint: .redocly.yaml` (the file beside `apionly.yaml`), `defaults.openapi.outputName: openapi.yaml`, `build.staging: build/staging`, `build.dist: build/dist`, `reports.lint: build/reports/lint`, `toolchain.redocly: "@redocly/cli@2.52.0"`, `channels.file.directory: build/publish` with `clean: true`, and `targets.<name>.openapi.bundle: bundles/<name>.yaml`.
- Each contract's version is in `contracts/specs/openapi/bundles/<name>.bundle.properties` as `version=1.0.0`; the bundle root needs `info.version: 0.0.0`.
- `build --target <t>` bundles, stamps and lints; `lint` fails on any YAML file under `specs/` no target references; `publish --target <t> --channel file` writes `build/publish/<t>/<version>/`; `changed --since <ref> --quiet` lists the targets a change reaches.
- Every Publisher run rebuilds `contracts/build/staging/`, so no two runs may overlap.

## Facts: API-Only Subscriber {api-only-subscriber-version}

- Gradle plugin `com.arc-e-tect.api-only-subscriber`, version `{api-only-subscriber-version}`; Gradle 8 or newer, Java 21 or newer.
- An API a project calls is declared with `subscribeAsClient('<target>') { version = ... }`; `version` is required, and `apiContractVersion` is not read.
- In a subproject, read its own `gradle.properties` with `findProperty('<name>')`; `providers.gradleProperty` does not see a subproject's own file.
- Tasks: `fetchApiSpec<Target>`, `verifyApiSpec<Target>` (target in camel case), `fetchApiSpec`, `verifyApiSpec`; `check` depends on `verifyApiSpec`, `processResources` on the fetches, which copies each contract to `contracts/<target>/` on the classpath.
- `apionly.lock`, beside the calling project's build file, holds one entry per subscription.
- A locked version whose bytes changed is refused: `the published contract for '<target>' <version> is not the one recorded in apionly.lock`.

## The Gradle wiring

The root `build.gradle` builds and publishes every contract, named after the contract, one Publisher run at a time:

```groovy
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

plugins {
    id 'base'
    id 'com.arc-e-tect.api-only-subscriber' version '{api-only-subscriber-version}' apply false
}

def contracts = file('contracts')
def apiContracts = ['<target-a>', '<target-b>']
def npm = System.getProperty('os.name').toLowerCase().contains('windows') ? 'npm.cmd' : 'npm'

abstract class ApiOnlyPublisherRuns implements BuildService<BuildServiceParameters.None> {}
def publisherRuns = gradle.sharedServices.registerIfAbsent('apiOnlyPublisherRuns', ApiOnlyPublisherRuns) {
    maxParallelUsages = 1
}

def installApiOnlyPublisher = tasks.register('installApiOnlyPublisher', Exec) {
    workingDir contracts
    inputs.files(new File(contracts, 'package.json'), new File(contracts, 'package-lock.json')).withPropertyName('manifests')
    outputs.file(new File(contracts, 'node_modules/.package-lock.json')).withPropertyName('installation')
    commandLine npm, 'ci', '--no-audit', '--no-fund'
}

def buildApiContracts = apiContracts.collect { target ->
    def buildApiContract = tasks.register("buildApiContract${target.capitalize()}", Exec) {
        dependsOn installApiOnlyPublisher
        usesService publisherRuns
        workingDir contracts
        inputs.dir(new File(contracts, 'specs')).withPropertyName('specification')
        inputs.files(new File(contracts, 'apionly.yaml'), new File(contracts, 'package-lock.json')).withPropertyName('configuration')
        outputs.dir(new File(contracts, "build/dist/${target}")).withPropertyName('bundle')
        commandLine npm, 'run', '--silent', 'apionly', '--', 'build', '--target', target
    }
    tasks.register("publishApiContract${target.capitalize()}", Exec) {
        dependsOn buildApiContract
        usesService publisherRuns
        workingDir contracts
        inputs.dir(new File(contracts, "build/dist/${target}")).withPropertyName('bundle')
        outputs.dir(new File(contracts, "build/publish/${target}")).withPropertyName('archive')
        commandLine npm, 'run', '--silent', 'apionly', '--', 'publish', '--target', target, '--channel', 'file'
    }
    buildApiContract
}

def lintApiContracts = tasks.register('lintApiContracts', Exec) {
    group = 'verification'
    dependsOn buildApiContracts
    usesService publisherRuns
    workingDir contracts
    inputs.dir(new File(contracts, 'specs')).withPropertyName('specification')
    inputs.dir(new File(contracts, 'build/dist')).withPropertyName('bundles')
    outputs.dir(new File(contracts, 'build/reports/lint')).withPropertyName('reports')
    commandLine npm, 'run', '--silent', 'apionly', '--', 'lint'
}
tasks.named('check') { dependsOn lintApiContracts }
```

The calling project's `build.gradle` subscribes:

```groovy
apiOnlySubscriber {
    channel {
        type = 'file'
        directory = rootProject.file('contracts/build/publish').path
    }
    subscribeAsClient('<target-a>') {
        version = findProperty('<target-a>ApiVersion')
    }
}
tasks.named('fetchApiSpec<TargetA>') { dependsOn ':publishApiContract<TargetA>' }
```

## Step 1: Investigate and propose

Read only; do not edit anything in this step.
Find out and report:

1. The Gradle version, the DSL, the projects in `settings.gradle`, the configuration cache, the test framework, and which Node.js and npm versions are installed.
2. Whether `contracts/` exists, and its targets; if not, where the API descriptions are today.
3. The calling project, and the APIs it calls, by target name.
4. Whether the calling project implements any API; if it does, tell me.
5. The CI system.

Then present the plan and wait for my approval.

## Step 2: The library, building on its own

1. Create or complete `contracts/`: `package.json`, the Publisher, the fragments, a version file per contract, `.redocly.yaml` and `apionly.yaml`.
2. **Prove the lint fails first**: add an unreferenced YAML file under `contracts/specs/openapi/components/common/`, run `npm run apionly -- build` and `npm run apionly -- lint` in `contracts/`, show the failure naming the file, delete it, and show both succeed.

## Step 3: The failing contract test

1. Add `<target>ApiVersion=1.0.0` for each API to the calling project's `gradle.properties`.
2. Apply the Subscriber in the root build with `apply false` and in the calling project without a version; make its test task run JUnit and pass each property with `systemProperty '<target>ApiVersion', findProperty('<target>ApiVersion')`.
3. Create one test method per API that reads `/contracts/<target>/openapi.yaml` and asserts it contains `version: ` followed by that property.
4. Run `./gradlew :<project>:test`, and show me that every method fails.

## Step 4: The wiring, green

1. Add the root build's tasks and the calling project's subscriptions from the wiring above.
2. Run `./gradlew check`; show `Subscribed to ...` for each API, the passing tests and `apionly.lock`.
3. Run `./gradlew check --parallel --configuration-cache` twice, and show the publish and fetch tasks `UP-TO-DATE` the second time.

## Step 5: Prove each guard, then undo it

1. **A shared fragment changed**: change a fragment in `components/common/`, show which targets `changed --since HEAD --quiet` lists, run `./gradlew check --continue`, and show the refusals for each.
2. **The change done properly**: raise each affected version file and property, run `./gradlew check`, and show `Updated ...` for each.

## Step 6: CI

Ask before doing this step.
Add a CI job with Node.js 24 and Java 21 that runs `./gradlew check --parallel`, fails when `git diff --exit-code -- '*/apionly.lock'` shows a change, and keeps `contracts/build/reports/lint/`.
For GitHub Actions, use `actions/checkout@v7`, `actions/setup-node@v6`, `actions/setup-java@v5`, `gradle/actions/setup-gradle@v6` and `actions/upload-artifact@v7`.

## Hand over

1. Summarise what changed, and list the files to commit.
2. Offer to add a section like this to `AGENTS.md`, and write it only if I agree:

   ```markdown
   ## API contracts

   - The contracts are built from `contracts/specs/`. Run the Publisher only as `npm run apionly -- <command>` in `contracts/`.
   - A project calls an API with `subscribeAsClient`, at `<target>ApiVersion` in its own `gradle.properties`.
   - A change to a contract raises its version file and every calling project's property together, and commits the updated `apionly.lock` files.
   - Every YAML file under `contracts/specs/` must be referenced by a contract, or the lint fails.
   - Installing packages, and Gradle resolving plugins and contracts, need network access.
   ```
