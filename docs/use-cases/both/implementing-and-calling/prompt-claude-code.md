---
description: Make a Gradle project that implements one API contract and calls other APIs build against a verified copy of each, test first.
---

# Implement one API contract and call others, test first

Help me set up a Gradle project that **implements** one API contract and **calls** other APIs, so that its build pins and verifies every one of those contracts, with the API-Only Subscriber, and where needed the API-Only Publisher.

When we are done, `./gradlew check`:

- fetches the implemented contract at `apiContractVersion`, and puts it at the root of the classpath, `/openapi.yaml`;
- fetches each called API at its own version, and puts it at `/contracts/<target>/openapi.yaml`;
- verifies all of them against one committed `apionly.lock`;
- runs a test that proves every contract reaches the code.

## How to work

- Start in plan mode.
  Investigate first, then present a plan that lists every file you will create or change, and wait for my approval before you edit anything.
- Keep a todo list of the steps below, and update it as you finish each one.
- Work test first.
  Create each failing check before the change that makes it pass, run it, and show me the failure.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- Do not commit or push.
- **Never ask me for a credential, and never write one into any file.**
- **Never guess coordinates, target names or versions.** Ask me.
- Do not invent configuration keys, commands, tasks or options.
  Everything that exists is listed under the facts below.
  If you need something that is not listed, stop and ask me.

## Principles

- **One implemented contract per project.** If the project implements two, stop and tell me: each needs a project of its own.
- **Every called API has its own version**, in `gradle.properties` as `<target>ApiVersion`.
- **A contract that comes from somewhere else than the others gets a `channel { }` of its own** in its subscription; every setting it leaves out comes from the project's channel.
- **Fetched documents and `apionly.lock` are never edited by hand.**

## Facts: API-Only Subscriber 0.2.0

- Gradle plugin `com.arc-e-tect.api-only-subscriber`, version `0.2.0`; Gradle 8 or newer, Java 21 or newer.
- Configuration:

  ```groovy
  apiOnlySubscriber {
      channel {
          type = 'file'                                 // or 'maven', with groupId; the project's default
          directory = rootProject.file('contracts/build/publish').path
      }

      // The contract this project implements, at apiContractVersion.
      subscribe('<implemented>')

      // The APIs it calls, each at its own version; version is required.
      subscribeAsClient('<called>') {
          version = findProperty('<called>ApiVersion')
          // channel {                                  // only when this API comes from elsewhere;
          //     type = 'maven'                         // unset settings come from the project's channel
          //     groupId = '<group>'
          // }
      }
  }
  ```

- `subscribe` reads `apiContractVersion` from the project's properties; `subscribeAsClient` reads only its own `version`.
  In a subproject, read its own `gradle.properties` with `findProperty`; in a single-project build, `providers.gradleProperty` works too.
- A second `subscribe` to a different target fails the build with `apiOnlySubscriber already implements '<a>', so it cannot also implement '<b>'`; the same target with both methods fails too.
- Tasks: `fetchApiSpec<Target>` and `verifyApiSpec<Target>` per contract, and `fetchApiSpec` and `verifyApiSpec`; `check` depends on `verifyApiSpec`, and `processResources` on the fetches.
- `apionly.lock` holds one entry per contract: target, version, channel and a SHA-256 per document.
- It prints `Subscribed to <target> <version>` and `Updated <target> from <old> to <new>`.

## Where the contracts come from

This prompt does not set up the sources themselves.
For each contract, find out where it comes from, and follow the matching setup:

- a specification library in this repository, published to `contracts/build/publish` by root build tasks `publishApiContract<Target>`: the fetch depends on that task, `tasks.named('fetchApiSpec<Target>') { dependsOn ':publishApiContract<Target>' }`;
- a Maven repository: a `repositories { maven { name = '<name>'; url = uri('<url>'); credentials(PasswordCredentials) } }` entry, and `channel { type = 'maven'; groupId = '<group>' }`, on the project or inside that one subscription.

If a source is not set up yet, stop and tell me which provider or client use case applies.

## Step 1: Investigate and propose

Do not edit anything in this step.
Find out and report:

1. The Gradle version, the DSL, the projects, the configuration cache and the test framework.
2. The contract this project implements, and every API it calls: target names, where each comes from, and versions.
3. Which channel each of them resolves through, and so which subscriptions need a `channel { }` of their own.
4. How the project uses those descriptions today, and the CI system.

Then present the plan and wait for my approval.

## Step 2: The failing contract test

1. Add `apiContractVersion` and a `<target>ApiVersion` per called API to the project's `gradle.properties`, with comments saying which is implemented and which are called.
2. Make the test task run JUnit and pass each property to the test JVM.
3. Create one test method for the implemented contract at `/openapi.yaml`, and one per called API at `/contracts/<target>/openapi.yaml`, each asserting the document contains `version: ` followed by its property.
4. Run the project's tests, and show me that every method fails.

## Step 3: Subscribe, green

1. Add the `apiOnlySubscriber` block: one `subscribe`, one `subscribeAsClient` per called API, and the fetch dependencies the sources need.
2. Run `./gradlew check`; show `Subscribed to ...` for every contract, the passing tests, the resources under `build/resources/main/`, and `apionly.lock`.
3. Run `./gradlew check --configuration-cache` twice, and show every fetch `UP-TO-DATE` the second time.

## Step 4: Prove the roles, then undo

1. Change one called API's `subscribeAsClient` to `subscribe`, run `./gradlew help`, show the `already implements` refusal, and restore it.

## Hand over

1. Summarise what changed, and list the files to commit.
2. Offer to add a section like this to `CLAUDE.md`, and write it only if I agree:

   ```markdown
   ## API contracts

   - This project implements `<implemented>` (`subscribe`, `apiContractVersion`) and calls `<called>` (`subscribeAsClient`, `<called>ApiVersion`).
   - The implemented contract is at `/openapi.yaml` on the classpath; each called API at `/contracts/<target>/openapi.yaml`.
   - A new version of any contract is one property change, `./gradlew check`, and the updated `apionly.lock`, committed together.
   - Never edit `build/api-spec/` or `apionly.lock` by hand.
   ```
