---
description: Make this Gradle project implement a published API contract with the API-Only Subscriber, test first.
---

# Implement a published API contract in this project, test first

Help me make this Gradle project build against an API contract that it implements, and that another team or company publishes, with the API-Only Subscriber.
The contract is published as an archive, built by the API-Only Publisher, to a Maven repository.
This project implements the contract; it does not own it and does not change it.

When we are done, `./gradlew check`:

- fetches the contract at the version this project implements, verifies every document against the archive's manifest, and records the version and the hashes in a committed `apionly.lock`;
- verifies the fetched documents against `apionly.lock`;
- runs a test that proves the contract, at that version, reaches the code.

## How to work

- Begin with a read-only investigation.
  Then present a plan that lists every file you will create or change, and do not edit anything until I approve it.
- Keep your plan updated as you finish each step below.
- Work test first.
  Create each failing check before the change that makes it pass, run it, and show me the failure.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- **Network access**: Gradle needs it to resolve the Subscriber plugin, the test libraries and the contract.
  If the sandbox blocks network access, ask me to approve those commands with network access, or to run them myself, rather than working around the failure.
- Do not commit or push.
  At the end, tell me what to commit.
- **Never ask me for a credential, and never write one into any file.**
  If fetching fails on credentials, stop and tell me which property is missing.
- **Never guess coordinates.** If you cannot find the repository URL, the group id, the target name or the version, ask me.
- Do not invent configuration keys, tasks or options.
  Everything that exists is listed under the facts below.
  If you need something that is not listed, stop and ask me.

## Principles

- **These documents are fetched, not authored here.** Never edit files under `build/api-spec/`, never edit `apionly.lock` by hand, and never copy a fetched document into `src/`.
- **Credentials stay outside committed files.** The build file names the repository; the user name and token come from `~/.gradle/gradle.properties` or `ORG_GRADLE_PROJECT_*` environment variables.
- **A pre-release is allowed only deliberately**, with `allowPrerelease = true` and a comment saying it must be removed before release.
- **If this project, or this repository, owns the contract, stop and tell me.** That calls for a different setup.
- **If this project calls the API rather than implementing it, stop and tell me.** That calls for a client subscription, which is a different setup.

## Versions

Wherever one of these names appears below, in braces, it stands for this version:

| Name | Version |
|---|---|
| `{api-only-subscriber-version}` | `0.3.4` |

These are the versions this prompt was verified with. Use them unless I name newer ones; a newer release works the same unless its changelog says otherwise.

## Facts: API-Only Subscriber {api-only-subscriber-version}

- Gradle plugin `com.arc-e-tect.api-only-subscriber`, version `{api-only-subscriber-version}`, from the Gradle Plugin Portal.
  It needs Gradle 8 or newer and Java 21 or newer, and supports the configuration cache.
- Configuration:

  ```groovy
  repositories {
      mavenCentral()
      maven {
          name = 'apiContracts'                          // reads apiContractsUsername / apiContractsPassword
          url = uri('<the producer\'s Maven repository>')
          credentials(PasswordCredentials)
      }
  }

  apiOnlySubscriber {
      channel {
          type = 'maven'                                 // the default; the other type is 'file'
          groupId = '<the producer\'s group id>'
          // extension = 'tgz'                           // the default
      }
      subscribe('<target>') {
          // apiContractVersion = '1.1.0'                // else apiOnlySubscriber's, else the project property
          // allowPrerelease = false                     // the default
          // groupId = '...'                             // overrides channel.groupId for this contract
          // artifactId = '...'                          // defaults to the target name
      }
  }
  ```

- **The version a subscription fetches** is `-PapiContractVersion` on the command line, which overrides every other; otherwise the subscription's own `apiContractVersion`; otherwise `apiOnlySubscriber.apiContractVersion`; otherwise the project property `apiContractVersion`, from `gradle.properties` or `ORG_GRADLE_PROJECT_apiContractVersion`.
  The old name `version` fails the build and names `apiContractVersion`.
- The `maven` channel resolves `<groupId>:<artifactId>:<version>@tgz` through the project's `repositories`.
  The producer's repository holds, per release, `<artifactId>-<version>.tgz`, `.pom` and `-manifest.json`.
  The Subscriber cannot fetch from npm or GitHub releases.
- A named repository with `credentials(PasswordCredentials)` reads `<name>Username` and `<name>Password` as Gradle properties.
- Tasks: `fetchApiSpec<Target>` and `verifyApiSpec<Target>`, with the target in PascalCase, plus the aggregates `fetchApiSpec` and `verifyApiSpec`.
  `check` depends on `verifyApiSpec`, and `processResources` depends on the fetch.
  `verifyApiSpec` deliberately does not depend on the fetch, so `./gradlew check` fetches again and restores a hand-edited document, while `./gradlew verifyApiSpec` on its own reports it.
- The fetch unpacks the documents (`openapi.yaml`, `asyncapi.yaml`) and `manifest.json` into `build/api-spec/<target>/`, which is added as a resources directory, so they sit at the classpath root.
- **A project implements one contract.**
  A second contract it implements belongs in a project of its own, in a multi-project build.
  A second `subscribe` to a different contract fails the build with `apiOnlySubscriber already implements '<a>', so it cannot also implement '<b>'`.
- Gradle tasks that need a document read it through `apiOnlySubscriber.subscription('<target>').openapi` (or `.asyncapi`), which carries the dependency on the fetch.
- `apionly.lock`, beside `build.gradle`, records target, version, channel and a SHA-256 per document.
  It is generated and committed.
- What the Subscriber prints:
  - `Subscribed to <target> <version>`, and `Updated <target> from <old> to <new>`;
  - `subscription '<target>' resolves the pre-release version <version>.` when a pre-release is not allowed;
  - `the published contract for '<target>' <version> is not the one recorded in apionly.lock: ... A released version was rebuilt, or a tag was moved.` when a locked version comes back with different bytes;
  - `the contract for '<target>' has drifted from apionly.lock:` from `verifyApiSpec`.

## Step 1: Investigate and propose

Read only; do not edit anything in this step.
Find out and report:

1. The Gradle version, the DSL (Groovy or Kotlin), whether the `java` plugin is applied, whether the configuration cache is on, whether repositories are declared centrally in `settings.gradle`, and the test framework.
2. The contract this project implements: the repository URL, the group id, the target name and the version.
   Ask me for anything you cannot find.
3. How this project uses the API description today: a committed copy, a generator, classpath access in code, and at which paths.
4. How credentials for Maven repositories are provided today, locally and in CI, by property name only.
5. The CI system.

Then present the plan and wait for my approval.

## Step 2: The failing contract test

1. Add `apiContractVersion=<version>` to `gradle.properties`, with a comment saying it is the version of the contract this project implements.
   If this project implements more than one contract, stop and tell me: each contract needs a project of its own, and that is a change to the build's structure I have to agree to.
2. Make sure the test task runs JUnit and passes the property to the test JVM: `systemProperty 'apiContractVersion', providers.gradleProperty('apiContractVersion').get()`.
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

   If a committed copy of the document is on the classpath today, tell me before going on: it has to go in step 3.
4. Run `./gradlew test`, and show me that `ApiContractTest` fails.

## Step 3: Apply the Subscriber

1. Apply the plugin.
2. Declare the repository with a `name` and `credentials(PasswordCredentials)`, in `settings.gradle` if the project declares repositories there.
3. Add the `apiOnlySubscriber` block with the `maven` channel and the one `subscribe`.
4. Point everything that read the description at the fetched document: the classpath resource, or `apiOnlySubscriber.subscription('<target>').openapi` for a Gradle task, never a path string.
   Remove any committed copy.
5. Tell me which Gradle properties to set locally and which environment variables CI needs, by name only.

## Step 4: Green

1. Run `./gradlew check`.
   Show `Subscribed to <target> <version>`, that `ApiContractTest` passes, and that the build succeeds.
2. Show `apionly.lock`.
3. Run `./gradlew check` again, with `--configuration-cache` if the project uses it, and show that the fetch is `UP-TO-DATE`.

## Step 5: Prove each guard, then undo it

1. **A hand-edited fetched document**: append a comment to `build/api-spec/<target>/openapi.yaml`, run `./gradlew verifyApiSpec` on its own, show the `has drifted from apionly.lock` failure, then run `./gradlew fetchApiSpec` to restore the document.
2. **A pre-release, not allowed**: only if the producer has published a pre-release of the contract, subscribe to it without `allowPrerelease`, run `./gradlew fetchApiSpec`, show the refusal, and restore the version.
   Skip this and tell me if no pre-release exists.

## Step 6: CI

Ask before doing this step.
Add a CI job that sets up Java 21, provides the repository credentials as `ORG_GRADLE_PROJECT_<name>Username` and `ORG_GRADLE_PROJECT_<name>Password` from CI secrets, runs `./gradlew check`, and fails when `git diff --exit-code -- apionly.lock` shows a change.
For GitHub Actions, use `actions/checkout@v7`, `actions/setup-java@v5` and `gradle/actions/setup-gradle@v6`.

## Hand over

1. Summarise what changed, list the files to commit (`build.gradle`, `settings.gradle` if changed, `gradle.properties`, `apionly.lock`, the test, and the CI file if one was added), and list the credentials to set, by name only.
2. Mention that publishing is passive: nothing tells this project a new version exists, and update bots do not read `apiContractVersion` unless configured to.
3. Offer to add a section like this to `AGENTS.md`, and write it only if I agree:

   ```markdown
   ## The published API contract this project implements

   - The contract this project implements is fetched by the API-Only Subscriber into `build/api-spec/`. Never edit it, and never copy it into `src/`.
   - `apionly.lock` is generated. Never edit it by hand.
   - To take a new contract version, change `apiContractVersion` in `gradle.properties`, run `./gradlew check`, and commit the build change with the updated `apionly.lock`.
   - Repository credentials come from Gradle properties outside the project, never from committed files.
   - A Gradle build that resolves plugins or contracts needs network access.
   ```
