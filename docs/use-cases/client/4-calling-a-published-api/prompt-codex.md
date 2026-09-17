---
description: Make this Gradle project call published APIs against verified, versioned contracts with the API-Only Subscriber, test first.
---

# Call published APIs against verified contracts, test first

Help me make this Gradle project build against the contracts of the APIs it **calls**, which other teams or companies publish, with the API-Only Subscriber.
Each contract is published as an archive, built by the API-Only Publisher, to a Maven repository.
This project does not implement these APIs, does not own their contracts, and does not change them.

When we are done, `./gradlew check`:

- fetches each contract at the version this project calls, verifies every document against the archive's manifest, and records the versions and the hashes in a committed `apionly.lock`;
- verifies the fetched documents against `apionly.lock`;
- runs a test that proves each contract, at its version, reaches the code.

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
- **Never ask me for a credential, and never write one into any file.**
  If fetching fails on credentials, stop and tell me which property is missing.
- **Never guess coordinates.** If you cannot find a repository URL, a group id, a target name or a version, ask me.
- Do not invent configuration keys, tasks or options.
  Everything that exists is listed under the facts below.
  If you need something that is not listed, stop and ask me.

## Principles

- **This project calls these APIs.** If it implements one of them, stop and tell me: that is a different subscription.
- **These documents are fetched, not authored here.** Never edit files under `build/api-spec/`, never edit `apionly.lock` by hand, and never copy a fetched document into `src/`.
- **Credentials stay outside committed files.**
- **Each API has its own version**, because each provider releases on its own schedule.
- **A pre-release is allowed only deliberately**, with `allowPrerelease = true` on that one subscription and a comment saying it must be removed before release.

## Versions

Wherever one of these names appears below, in braces, it stands for this version:

| Name | Version |
|---|---|
| `{api-only-subscriber-version}` | `0.3.0` |

## Facts: API-Only Subscriber {api-only-subscriber-version}

- Gradle plugin `com.arc-e-tect.api-only-subscriber`, version `{api-only-subscriber-version}`; Gradle 8 or newer, Java 21 or newer; supports the configuration cache.
- Configuration:

  ```groovy
  repositories {
      mavenCentral()
      maven {
          name = 'apiContracts'                          // reads apiContractsUsername / apiContractsPassword
          url = uri('<the provider\'s Maven repository>')
          credentials(PasswordCredentials)
      }
  }

  apiOnlySubscriber {
      channel {
          type = 'maven'                                 // the default
          groupId = '<the provider\'s group id>'
      }
      subscribeAsClient('<target>') {
          version = providers.gradleProperty('<target>ApiVersion')   // required: no default
          // allowPrerelease = false                     // the default
          // groupId = '...'                             // another provider's group
          // artifactId = '...'                          // defaults to the target name
      }
  }
  ```

- A client subscription sets its own `version`; it does not read `apiContractVersion`, which is the version of a contract the project implements.
  Without one, the build fails with `client subscription '<target>' declares no version`.
- The `maven` channel resolves `<groupId>:<artifactId>:<version>@tgz` through the project's `repositories`; a subscription can set a `channel { }` of its own, and every setting it leaves out comes from the project's channel.
- A named repository with `credentials(PasswordCredentials)` reads `<name>Username` and `<name>Password` as Gradle properties.
- Tasks: `fetchApiSpec<Target>` and `verifyApiSpec<Target>` (target in camel case), and the aggregates `fetchApiSpec` and `verifyApiSpec`; `check` depends on `verifyApiSpec`, and `processResources` on the fetches.
- Each fetch unpacks into `build/api-spec/<target>/`, and, with the `java` plugin, `processResources` copies the documents to `contracts/<target>/` on the classpath.
- `apionly.lock` holds one entry per subscription: target, version, channel and a SHA-256 per document.
- Gradle tasks that need a document read it through `apiOnlySubscriber.subscription('<target>').openapi`, which carries the dependency on the fetch.
- What the Subscriber prints: `Subscribed to <target> <version>`, `Updated <target> from <old> to <new>`, `subscription '<target>' resolves the pre-release version <version>.`, `the published contract for '<target>' <version> is not the one recorded in apionly.lock`, and `the contract for '<target>' has drifted from apionly.lock:`.

## Step 1: Investigate and propose

Read only; do not edit anything in this step.
Find out and report:

1. The Gradle version, the DSL, whether the `java` plugin is applied, whether the configuration cache is on, whether repositories are declared in `settings.gradle`, and the test framework.
2. The APIs this project calls: for each, the repository URL, the group id, the target name and the version.
   Ask me for anything you cannot find.
3. How this project uses those descriptions today: committed copies, a client generator, and at which paths.
4. Whether this project also implements an API; if it does, tell me, because that changes the setup.
5. How credentials for Maven repositories are provided today, by property name only, and the CI system.

Then present the plan and wait for my approval.

## Step 2: The failing contract test

1. Add `<target>ApiVersion=<version>` to `gradle.properties` for each API, with a comment saying these are the versions of the APIs this project calls.
2. Make the test task run JUnit and pass each property to the test JVM with `systemProperty '<target>ApiVersion', providers.gradleProperty('<target>ApiVersion').get()`.
3. Create a test with one test method per API, each reading `/contracts/<target>/openapi.yaml` from the classpath and asserting that it exists and contains `version: ` followed by that API's property.
4. Run `./gradlew test`, and show me that every method fails.

## Step 3: Apply the Subscriber

1. Apply the plugin, and declare the repository with a `name` and `credentials(PasswordCredentials)`.
2. Add the `apiOnlySubscriber` block with the `maven` channel and one `subscribeAsClient` per API.
3. Point everything that read a description at the fetched document: the classpath resource, or `apiOnlySubscriber.subscription('<target>').openapi` for a Gradle task such as a client generator.
   Remove any committed copy.
4. Tell me which Gradle properties to set locally and which environment variables CI needs, by name only.

## Step 4: Green

1. Run `./gradlew check`; show `Subscribed to <target> <version>` for each API, the passing tests, and `apionly.lock`.
2. Run `./gradlew check --configuration-cache` twice, and show every fetch `UP-TO-DATE` the second time.

## Step 5: Prove each guard, then undo it

1. **A hand-edited fetched document**: append a comment to one `build/api-spec/<target>/openapi.yaml`, run `./gradlew verifyApiSpec` on its own, show the `has drifted` failure, and run `./gradlew fetchApiSpec`.
2. **A pre-release, not allowed**: only if a provider has published a pre-release, subscribe to it without `allowPrerelease`, show the refusal, and restore the version; otherwise skip this and tell me.

## Step 6: CI

Ask before doing this step.
Add a CI job with Java 21 that provides the repository credentials as `ORG_GRADLE_PROJECT_<name>Username` and `ORG_GRADLE_PROJECT_<name>Password` from CI secrets, runs `./gradlew check`, and fails when `git diff --exit-code -- apionly.lock` shows a change.
For GitHub Actions, use `actions/checkout@v7`, `actions/setup-java@v5` and `gradle/actions/setup-gradle@v6`.

## Hand over

1. Summarise what changed, list the files to commit, and list the credentials to set, by name only.
2. Mention that publishing is passive: nothing tells this project a new version exists.
3. Offer to add a section like this to `AGENTS.md`, and write it only if I agree:

   ```markdown
   ## The APIs this project calls

   - Their contracts are fetched by the API-Only Subscriber into `build/api-spec/`, and are on the classpath under `contracts/<target>/`. Never edit them, and never copy them into `src/`.
   - `apionly.lock` is generated. Never edit it by hand.
   - To take a new version of an API, change its `<target>ApiVersion` in `gradle.properties`, run `./gradlew check`, and commit the change with the updated `apionly.lock`.
   - Repository credentials come from Gradle properties outside the project, never from committed files.
   - Installing packages, and Gradle resolving plugins and contracts, need network access.
   ```
