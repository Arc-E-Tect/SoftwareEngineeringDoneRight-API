---
description: Make this Gradle project call APIs released by a specification library in another repository, with a way to work ahead of a release, test first.
---

# Call APIs from a specification library in another repository, test first

Help me make this Gradle project build against the contracts of the APIs it **calls**, with the API-Only Subscriber.
The contracts come from a specification library in another repository that I can check out, which releases them to a Maven repository with the API-Only Publisher.
This project does not implement these APIs; a change to a contract is made in the library, never here.

When we are done:

- `./gradlew check` fetches each contract at the released version this project calls, verifies every document against the archive's manifest, records the versions and hashes in a committed `apionly.lock`, verifies the fetched documents, and runs a test that proves each contract reaches the code;
- I know exactly how to build against an unreleased contract change from my checkout of the library, and how to switch back.

## How to work

- Begin with a read-only investigation.
  Then present a plan that lists every file you will create or change, and do not edit anything until I approve it.
- Keep your plan updated as you finish each step below.
- Work test first.
  Create each failing check before the change that makes it pass, run it, and show me the failure.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- **Network access**: installing packages, running bundlers, and Gradle resolving plugins, libraries and contracts need it.
  If the sandbox blocks network access, ask me to approve those commands with network access, or to run them myself, rather than working around the failure.
- Do not commit or push, in this repository or in the library.
- **Never ask me for a credential, and never write one into any file.**
- **Never guess coordinates.** If you cannot find the repository URL, the group id, a target name or a version, ask me.
- Do not invent configuration keys, commands, tasks or options.
  Everything that exists is listed under the facts below.
  If you need something that is not listed, stop and ask me.

## Principles

- **This project calls these APIs.** If it implements one of them, stop and tell me.
- **Subscribe to releases.** The checkout of the library is only for working ahead of a release, and never what CI builds against.
- **Contract changes belong in the library**, proposed in a pull request that raises the contract's version.
- **Each API has its own version**, in `gradle.properties` as `<target>ApiVersion`.
- **Fetched documents and `apionly.lock` are never edited by hand.**

## Facts: API-Only Subscriber 0.2.0

- Gradle plugin `com.arc-e-tect.api-only-subscriber`, version `0.2.0`; Gradle 8 or newer, Java 21 or newer.
- Configuration, against releases:

  ```groovy
  repositories {
      mavenCentral()
      maven {
          name = 'apiContracts'                          // reads apiContractsUsername / apiContractsPassword
          url = uri('<the library\'s Maven repository>')
          credentials(PasswordCredentials)
      }
  }

  apiOnlySubscriber {
      channel {
          type = 'maven'
          groupId = '<the library\'s group id>'
      }
      subscribeAsClient('<target>') {
          version = providers.gradleProperty('<target>ApiVersion')   // required: no default
      }
  }
  ```

- Working ahead of a release, temporarily, on one subscription: a `channel { type = 'file'; directory = '../<library checkout>/build/publish' }` inside that `subscribeAsClient` block, `<target>ApiVersion` set to the pre-release, and `allowPrerelease = true`.
  Every setting a subscription's own channel leaves out comes from the project's channel, and the other subscriptions keep resolving through the project's channel.
- A client subscription sets its own `version`; it does not read `apiContractVersion`.
- Tasks: `fetchApiSpec<Target>`, `verifyApiSpec<Target>`, `fetchApiSpec`, `verifyApiSpec`; `check` depends on `verifyApiSpec`, and `processResources` on the fetches, which copies each contract to `contracts/<target>/` on the classpath.
- `apionly.lock` records target, version, channel (`maven` or `file`) and a SHA-256 per document.
- It prints `Subscribed to <target> <version>`, `Updated <target> from <old> to <new>`, and refuses a pre-release without `allowPrerelease` with `subscription '<target>' resolves the pre-release version <version>.`.

## Facts: API-Only Publisher 0.3.0, in the library's checkout

- Run it only as `npm run apionly -- <command>` after `npm ci`; never `npx api-only-publisher`.
- `build --target <t> --pre-release rc.1` and `publish --target <t> --pre-release rc.1 --channel file` publish `<version>-rc.1`, for the version in the contract's version file, to the library's `file` channel directory, `build/publish` when its `apionly.yaml` has `channels.file.directory: build/publish`.
  If the library's `apionly.yaml` has no `file` channel, stop and tell me.

## Step 1: Investigate and propose

Read only; do not edit anything in this step.
Find out and report:

1. The Gradle version, the DSL, whether the `java` plugin is applied, the configuration cache, repositories in `settings.gradle`, and the test framework.
2. The APIs this project calls: for each, the Maven repository URL, the group id, the target name and the released version.
3. Where the library is checked out, if it is, and whether its `apionly.yaml` has a `file` channel.
4. How credentials for Maven repositories are provided, by property name only, and the CI system.

Then present the plan and wait for my approval.

## Step 2: The failing contract test

1. Add `<target>ApiVersion=<released version>` to `gradle.properties` for each API.
2. Make the test task run JUnit and pass each property with `systemProperty '<target>ApiVersion', providers.gradleProperty('<target>ApiVersion').get()`.
3. Create one test method per API that reads `/contracts/<target>/openapi.yaml` from the classpath and asserts it contains `version: ` followed by that property.
4. Run `./gradlew test`, and show me that it fails.

## Step 3: Subscribe to the releases

1. Apply the plugin, declare the repository with a `name` and `credentials(PasswordCredentials)`, and add the `apiOnlySubscriber` block with the `maven` channel and a `subscribeAsClient` per API.
2. Run `./gradlew check`; show `Subscribed to ...`, the passing test and `apionly.lock`.
3. Add a CI job, after asking, that runs `./gradlew check` with the credentials as `ORG_GRADLE_PROJECT_*` secrets and fails when `apionly.lock` changed.

## Step 4: Rehearse working ahead of a release

Ask before this step, and undo all of it at the end.

1. In the library's checkout, run `npm ci`, then `build` and `publish` for one target with `--pre-release rc.1 --channel file`, and show the directory it wrote.
2. In this project, give that target's subscription a `channel { }` of its own on the checkout's `file` directory, set its version to the pre-release, and add `allowPrerelease = true` with a comment saying it is temporary.
3. Run `./gradlew check`, and show `Updated <target> from <released> to <pre-release>` and `channel file` in `apionly.lock`.
4. Remove the subscription's own channel and `allowPrerelease`, restore the released version and the lockfile, run `./gradlew check`, and show it green.

## Hand over

1. Summarise what changed, list the files to commit, and list the credentials to set, by name only.
2. Write down, for me, the three edits that switch to working ahead and the three that switch back.
3. Offer to add a section like this to `AGENTS.md`, and write it only if I agree:

   ```markdown
   ## The APIs this project calls

   - Their contracts are released by the specification library in <library repository>, and fetched by the API-Only Subscriber. Never edit `build/api-spec/` or `apionly.lock`.
   - A contract change is a pull request on the library, raising the contract's version, never an edit here.
   - Working ahead of a release gives one subscription a `channel { }` on a checkout of the library, with `allowPrerelease`; never commit or push that.
   - To take a release, change `<target>ApiVersion`, run `./gradlew check`, and commit the updated `apionly.lock`.
   - Installing packages, and Gradle resolving plugins and contracts, need network access.
   ```
