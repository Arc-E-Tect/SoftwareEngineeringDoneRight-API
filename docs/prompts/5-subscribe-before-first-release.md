# Build against an unreleased contract from another repository

Help me connect this implementation project (Gradle) to an API contract that lives in a **separate contracts repository** and has not been released yet. Both repositories are checked out on my machine; this setup only works when I can clone the contracts repository. The contracts repository publishes a pre-release to a local directory, and this project subscribes to it through the API-Only Subscriber's `file` channel. No registry is involved yet. When the contract is released for the first time, the only thing that should need to change in this project is the channel block.

Reference manual (read it if you can reach the web; the facts below are enough if you cannot):
https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/docs/before-anything-is-published.adoc

## Principles

- Build only what is needed now: a local directory, with no registry. Do not set up Maven repositories, credentials, or CI publishing.
- Build it so it lasts: the subscription, the lockfile, and a single `contract` provider that everything reading the document goes through.
- Pre-releases are explicit: set `allowPrerelease = true`, with a comment saying it must be removed before release.
- Machine-specific paths are never committed. The location of the contracts checkout comes from a Gradle property that has a sensible default.
- Never edit fetched documents or `apionly.lock` by hand.

## Facts: API-Only Publisher (in the contracts repository)

- Run it only as `npm run apionly -- <command>` (the npm script `"apionly": "api-only-publisher"`). **Never run `npx api-only-publisher`**: that unscoped name is not the package.
- `channels.file.directory` in `apionly.yaml` sets where `publish --channel file` writes: `<directory>/<target>/<version>/<target>-<version>.tgz`.
- Always run `build --version <v>` before `publish --version <v>`, with the same `<v>`. Otherwise the publish is refused.

## Facts: API-Only Subscriber (do not invent options beyond these)

- Gradle plugin `com.arc-e-tect.api-only-subscriber`, version `0.0.2`, from the Gradle Plugin Portal. Requires Gradle 8+ and Java 21+, and supports the configuration cache.
- Configuration:
  ```groovy
  apiOnlySubscriber {
      channel {
          type = 'file'          // or 'maven' (the default)
          directory = '...'      // file channel only
          groupId = '...'        // maven channel only
          // extension = 'tgz'
      }
      subscribe('<target>') {
          version = '...'        // required
          // allowPrerelease = false, groupId, artifactId, into
      }
      // lockfile defaults to apionly.lock beside the build file
  }
  ```
- Only the `maven` and `file` channels exist.
- Tasks: `fetchApiSpec`, `fetchApiSpec<Target>`, `verifyApiSpec`, `verifyApiSpec<Target>`. `check` depends on `verifyApiSpec`. When the `java` plugin is applied, `processResources` depends on the fetch. `verifyApiSpec` deliberately does **not** depend on the fetch.
- Documents land in `build/api-spec/<target>/`, together with `manifest.json`, and are added to the main resources. Read them through `apiOnlySubscriber.subscription('<target>').openapi` (or `.asyncapi`), providers that carry the task dependency, never through a path.
- `apionly.lock` is generated and committed.
- A pre-release version (`-rc.1`, `-SNAPSHOT`) is refused unless `allowPrerelease = true`.
- **Known limitation:** with the `java` plugin applied, each subscription's directory becomes a resource root, so a project with two or more subscriptions fails `processResources` on duplicate `manifest.json`/`openapi.yaml`. If this project will have more than one subscription, stop and tell me.

## Step 1: Investigate, then propose (do not edit anything yet)

Report:

1. Where the contracts repository is checked out, relative to this project. Ask me if you cannot find it. If it is not checked out and I have no access to clone it, stop: an unreleased contract then has to come as a pre-release from a registry.
2. Whether it has `apionly.yaml`, the `apionly` npm script, and the target this project needs (`npm run apionly -- targets`). If it has no Publisher setup, stop and tell me; that is a separate task.
3. Whether it already has a `channels.file` block.
4. This project's Gradle version and DSL (Groovy or Kotlin), whether the `java` plugin is applied, and whether it already subscribes to anything.
5. What in this project needs the document: the API-Only Suite, a generator, or the classpath.

Then propose a plan and wait for my go-ahead.

## Step 2: Implement

In the contracts repository, and only if I agreed to changes there:

1. Add `channels:` → `file:` → `directory: build/publish` to `apionly.yaml`.
2. Run `npm run apionly -- build --version 0.1.0-rc.1 --target <target>`, then `npm run apionly -- publish --version 0.1.0-rc.1 --target <target> --channel file`.

In this project:

3. Apply `id 'com.arc-e-tect.api-only-subscriber' version '0.0.2'`.
4. Configure it, adjusting the default path to the real relative location:
   ```groovy
   // Where unreleased contracts are published. Override with -PcontractsDir.
   def contractsDir = providers.gradleProperty('contractsDir')
           .orElse("${rootDir}/../contracts/build/publish").get()

   apiOnlySubscriber {
       channel {
           type = 'file'
           directory = contractsDir
       }
       subscribe('<target>') {
           version = providers.gradleProperty('<targetCamelCase>Version').orElse('0.1.0-rc.1')
           // Deliberate and temporary: remove before release.
           allowPrerelease = true
       }
   }

   // The one place this build says where its contract comes from.
   def contract = apiOnlySubscriber.subscription('<target>').openapi
   ```
   Wire everything that uses the document to `contract`.
5. After the first fetch, `apionly.lock` is ready to commit.

## Step 3: Verify, and show me the output

1. `./gradlew fetchApiSpec` prints `Subscribed to <target> 0.1.0-rc.1`, and `apionly.lock` exists.
2. `./gradlew check` passes, and `verifyApiSpec<Target>` reports that the files match the lockfile.
3. The pre-release guard works: temporarily remove `allowPrerelease`, run `./gradlew fetchApiSpec`, show the refusal, then restore the line.
4. Drift detection works: append a comment to `build/api-spec/<target>/openapi.yaml`, run `./gradlew verifyApiSpec`, show the failure, then run `./gradlew fetchApiSpec` to restore the file.
5. One loop: publish `0.1.0-rc.2` from the contracts repository, run `./gradlew fetchApiSpec check -P<targetCamelCase>Version=0.1.0-rc.2`, and show the diff in `apionly.lock`.

Do not report a step as passing unless you ran it.

## Finally

Summarise the changes, and show me exactly what will change in this project at the first release: the channel type, `groupId` in place of `directory`, a `repositories` entry, and removing `allowPrerelease`. Then offer to record the conventions in `CLAUDE.md` or `AGENTS.md`, and ask before writing.
