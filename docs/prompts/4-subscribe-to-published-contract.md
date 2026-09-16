# Subscribe a Gradle project to a published API contract

Help me make this Gradle project consume one or more published API contracts with the API-Only Subscriber. Someone else publishes the contracts as archives to a Maven repository: another team, or another repository. This project fetches them, locks their hashes in `apionly.lock`, verifies them on every `check`, and uses the documents through providers.

Reference manual (read it if you can reach the web; the facts below are enough if you cannot):
https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/docs/consuming-a-published-contract.adoc

## Principles

- These documents are fetched, not authored here. Never edit files under `build/api-spec/`, never edit `apionly.lock` by hand, and never copy a fetched document into `src/`.
- Credentials never go into a committed file. The build file names the repository; the username and token come from `~/.gradle/gradle.properties` or `ORG_GRADLE_PROJECT_*` environment variables. Do not ask me for a token, and do not write one anywhere.
- Everything that uses a document reads it through `apiOnlySubscriber.subscription('<target>').openapi` (or `.asyncapi`), assigned to exactly one provider per contract. Never through a path string.
- Pre-releases are allowed only with an explicit `allowPrerelease = true`, plus a comment saying it must be removed before release.
- If the contract is published from a library in this same repository, stop and tell me. There, the `file` channel reading a committed directory is the simpler setup, with no registry (manual 2).

## Facts: API-Only Subscriber (do not invent options beyond these)

- Gradle plugin `com.arc-e-tect.api-only-subscriber`, version `0.0.2`, from the Gradle Plugin Portal. Requires Gradle 8+ and Java 21+, and supports the configuration cache.
- Configuration:
  ```groovy
  apiOnlySubscriber {
      channel {
          type = 'maven'                    // the default; the only other type is 'file'
          groupId = 'com.example.contracts' // required by the maven channel
          // extension = 'tgz'              // default
      }
      subscribe('<target>') {
          version = '2.1.0'                 // required
          // allowPrerelease = false
          // groupId = '...'                // overrides channel.groupId for this target
          // artifactId = '...'             // defaults to the target name
          // into = layout.buildDirectory.dir('api-spec/<target>')
      }
      // lockfile = layout.projectDirectory.file('apionly.lock')   // default
  }
  ```
- The `maven` channel resolves `groupId:artifactId:version@tgz` through the project's declared `repositories`. It cannot fetch from npm or GitHub releases.
- A named repository with `credentials(PasswordCredentials)` reads `<name>Username` and `<name>Password` from Gradle properties (for example `ORG_GRADLE_PROJECT_apiContractsUsername`).
- Tasks: `fetchApiSpec`, `fetchApiSpec<Target>` (target in camel case), `verifyApiSpec`, `verifyApiSpec<Target>`. `check` depends on `verifyApiSpec`. With the `java` plugin, `processResources` depends on the fetch. `verifyApiSpec` deliberately does **not** depend on the fetch.
- The fetch checks every document against the archive's own `manifest.json`, writes `apionly.lock`, and refuses a locked version whose bytes have changed.
- Documents land in `build/api-spec/<target>/`, together with `manifest.json`. With the `java` plugin, each subscription's directory is added as a resource root.
- **Known limitation in 0.0.2:** with two or more subscriptions and the `java` plugin, `processResources` fails with `Entry manifest.json is a duplicate`. The workaround goes **after** the `apiOnlySubscriber` block:
  ```groovy
  sourceSets.main.resources.setSrcDirs(['src/main/resources'])   // add any other resource dirs this project uses
  tasks.named('processResources') {
      ['<target-a>', '<target-b>'].each { target ->
          from(apiOnlySubscriber.subscription(target).into) { into "contracts/$target" }
      }
  }
  ```
  The documents are then on the classpath at `contracts/<target>/openapi.yaml`.

## Step 1: Investigate, then propose (do not edit anything yet)

Report:

1. The Gradle version and DSL (Groovy or Kotlin), whether the `java` plugin is applied, whether the configuration cache is enabled, and whether repositories are centralised in `settings.gradle` (`dependencyResolutionManagement`).
2. The contracts to subscribe to: repository URL, group id, target names and versions. Ask me for anything you cannot find; do not guess coordinates.
3. How this project uses API documents today: API-Only Suite, an OpenAPI generator, classpath access in code (which resource paths), or committed copies. If committed copies exist, say so, because https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/docs/prompts/6-migrate-checked-in-document.md may be the better starting point.
4. How credentials for the repository are provided today, locally and in CI.

Give me a plan that lists every file you will create or change, and wait for my go-ahead.

## Step 2: Implement

1. Apply the plugin.
2. Declare the repository with a `name` and `credentials(PasswordCredentials)`, in `settings.gradle` if the project centralises repositories there.
3. Add the `apiOnlySubscriber` block with the agreed versions.
4. Create one provider per contract, for example `def userAccountContract = apiOnlySubscriber.subscription('user-account').openapi`, and wire everything that uses the document to it.
5. With two or more subscriptions and the `java` plugin, apply the workaround above, and update any code that reads the documents from the classpath to the `contracts/<target>/` paths.
6. Tell me which Gradle properties to set locally and which environment variables CI needs, without writing any values.

## Step 3: Verify, and show me the output

1. `./gradlew fetchApiSpec` prints `Subscribed to <target> <version>` for each subscription. Show `apionly.lock`.
2. `./gradlew build` succeeds. List `build/resources/main/` to show where the documents ended up.
3. Drift detection works: append a comment to one fetched `openapi.yaml`, run `./gradlew verifyApiSpec`, show the failure, then run `./gradlew fetchApiSpec` to restore the file.
4. A second `./gradlew build` succeeds (with `--configuration-cache` if the project uses it).

If fetching fails on credentials, stop and tell me which property is missing; do not work around it. Do not report a step as passing unless you ran it.

## Finally

Summarise what changed, and remind me to commit `apionly.lock` together with the build file. If no update bot (Renovate or Dependabot) watches the contract coordinates, mention that too. Then offer to add a short section to `CLAUDE.md` or `AGENTS.md` recording that documents under `build/api-spec/` are fetched and never edited, that `apionly.lock` is generated, and that upgrades happen by changing the version and running `./gradlew fetchApiSpec`. Ask before writing it.
