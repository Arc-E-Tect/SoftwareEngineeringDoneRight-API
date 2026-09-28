---
description: Adopt the contract tests and stubs the API-Only TranscriberJ generates, with their fixtures, test first.
---

# Adopt generated contract tests, their fixtures and their stubs

Help me adopt, in this Gradle project, the contract tests the API-Only TranscriberJ's REST Docs emitter generates, and the stubs its WireMock emitter generates: run the same generated tests against the real service and against a WireMock double, write the fixtures they need, and report what they find.

When we are done:

- every generated test passes on the double;
- every stateful case -- success and not found -- has a real `arrangeState`, and the stateful tests pass in random order, twice on the same state;
- every failure on the real service is reported to me as a finding before it is fixed, starting from the first stateless failure;
- every response the generation report lists as uncovered because it depends on behaviour -- a `409`, a `422` -- has one hand-written contract test on the same interface-and-hook pattern;
- no generated snippet is published;
- published documentation comes from documentation tests, not from contract tests;
- `./gradlew check` passes.

## How to work

- Begin with a read-only investigation.
  Then present a plan that lists every file you will create or change, and do not edit anything until I approve it.
- Keep your plan updated as you finish each step below.
- Work test first.
  Create each failing check before the change that makes it pass, run it, and show me the failure.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- Do not commit, push, or change anything outside this project.
  At the end, tell me what to commit.
- Do not invent generated classes, constants or methods.
  Read what was generated, under `build/generated/sources/transcriberj/` and `build/generated/files/transcriberj/`, and use only what is there.
  If you need something that is not, stop and ask me.
- **Network access**: Gradle resolving plugins and test libraries needs it, and so does the Publisher if this project builds its own contract.
  If the sandbox blocks network access, ask me to approve those commands with network access, or to run them myself, rather than working around the failure.

## Principles

- **Generated stateless tests are not independent of each other.**
  Invalid-request, not-acceptable and unsupported-media-type cases start from the same valid request; one the service wrongly accepts may leave state behind, and later cases then answer differently.
  Read the first failing stateless test first, treat later failures as possibly its consequence, fix, and re-run on fresh state before diagnosing the next.
  Do not work around this in the tests.
- **Stateful tests are independent when their fixture is correct.**
  `arrangeState` sets exactly the state its case needs, whatever ran before: it inserts what a read needs, and deletes what a create or a not-found case must not find.
- **A failure on the real service is a finding about the service**, not about the test.
  Report it, with the message, before changing anything; never change a generated test, and never change the service without telling me.
- **Generated tests verify; they do not document.**
  Never include a snippet a contract test writes in published documentation.
- **Every response the contract can express is generated.** Hand-write a contract test only for a response the report lists as depending on behaviour or state the contract does not describe; business rules are behaviour tests.
- **Generated code is for tests.** Never add `main` to a subscription's `sourceSets`, and never make application code import a generated class.
- **Never edit generated sources or mapping files.**

## Versions

Wherever one of these names appears below, in braces, it stands for this version:

| Name | Version |
|---|---|
| `{api-only-transcriberj-version}` | `0.13.0` |
| `{api-only-transcriberj-restdocs-version}` | `0.4.0` |
| `{api-only-transcriberj-wiremock-version}` | `0.1.0` |

These are the versions this prompt was verified with. Use them unless I name newer ones; a newer release works the same unless its changelog says otherwise.

## Facts: API-Only TranscriberJ {api-only-transcriberj-version}

- For every operation, `<Operation>ContractCases.CASES` holds its contract cases: `ContractCase` records with `id`, `kind` (`SUCCESS`, `NOT_FOUND`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE`, `INVALID_REQUEST`), `requiresState`, `variant`, `request` (a `ContractRequest`), `expectedStatus` and more.
- A case is derived only for a status the operation declares exactly; `invalidRequestStatus`, `400` by default, is the invalid-request status.
- `build/reports/transcriberj/<contract>.txt` lists, under *Response coverage*, every declared response with the cases that cover it, or its reason; under *Constraint coverage*, every constraint on request input; and the *Gaps*, operations that constrain input without declaring the invalid-request status.
- The valid values: `requiredBody()`, `fullBody()`, `requiredRequest()`, `fullRequest()`, `noBodyRequest()`.
- A subscription configures each emitter in an `emitter('<id>') { sourceSets = [...]; options = [...] }` block. `schemaClasses` is `perSourceSet` (default) or `shared`.

## Facts: the REST Docs emitter {api-only-transcriberj-restdocs-version}

- With `options = [tests: 'true']`, it generates `<basePackage>.restdocs.<Operation>ContractTests` for every operation with a case: one `default` test per case, and the hooks `client()`, `arrangeState(ContractCase)`, `arrangeStatelessCase(ContractCase)` and `generatedDocumentationPrefix()`.
- `arrangeState` runs before every success and not-found case, and by default throws `FixtureNotImplementedException`, whose message ends with a suggested implementation.
- `arrangeStatelessCase` runs before every other case, and by default does nothing.
- The tests need `org.springframework.restdocs:spring-restdocs-webtestclient` and `org.junit.jupiter:junit-jupiter-api` in the source set they are generated into.
- Every test calls `document()` to validate the response with `responseFields()`; the snippets go under `<prefix>/<operationId>/<caseId>/`.
- A failure message starts `<id> (<kind>: <description>): expected <status>, received <status>.` and goes on with the likely cause.

## Facts: the WireMock emitter {api-only-transcriberj-wiremock-version}

- By default it writes mapping files, on no source set, to `build/generated/files/transcriberj/<contract>/wiremock`: `mappings/<operationId>/<caseId>.json`, one exact stub per case, fallbacks, and `__files/`.
- `package<Contract>Wiremock` packages them as `build/distributions/<contract>-wiremock-<contractVersion>.zip`.
- Options: `format` (`files` or `java`), `priority` (`1` by default), `fallbacks` (`true` by default).
- WireMock deletes the mapping files in its working directory when stubs are reset or removed: point a server at a fresh copy of the unpacked archive.

## Step 1: Investigate and propose

Read only; do not edit anything in this step.
Find out and report:

1. The TranscriberJ version, the subscription's `basePackage`, `sourceSets` and `schemaClasses`, and which emitters are applied, with their options.
   If any version is older than those above, stop and tell me.
2. From the report: the counts of cases per kind, every uncovered response with its reason, every gap, and every uncovered constraint.
3. The test suites the project has, what each tests, and whether one sends real HTTP to the running service.
4. How the service keeps state, and how a test can put it in a given state.
5. The web framework, and how the service binds JSON.

Then present the plan: the source sets and emitter blocks, the base class of each target, the leaves, what each `arrangeState` does per case, the hand-written contract tests for the uncovered responses, and where published documentation comes from.
Wait for my approval.

## Step 2: The double

1. Configure the emitters, and a suite that runs the generated tests against WireMock loading the unpacked archive, with `arrangeState` doing nothing.
2. Run it, and show me that every generated test passes.
   A failure here is a finding about the stubs or the configuration: report it.

## Step 3: The real service

1. A suite that runs the same generated tests against the service, started for real, with real HTTP.
2. Run it with no `arrangeState` overridden, and show me each `FixtureNotImplementedException`.
3. Implement `arrangeState` in each leaf, one kind at a time, from the suggestion; run after each.
4. Report every other failure to me as a finding -- the first stateless failure first -- with its message and the cause it points at, and wait before fixing the service.
   After each fix, re-run on fresh state.

## Step 4: Prove the stateful tests are independent

1. Run the real suite twice without resetting state, and show both results.
2. Run it in a random method and class order, and show the result.

## Step 5: What stays hand-written

1. For each response the report lists as depending on behaviour or state the contract does not describe, one hand-written contract test: an interface with a `default` test and a hook of its own, in the source set both targets share, the request built from the generated classes, and the response checked with the generated `*Docs` descriptors.
2. Implement its hook on both targets.

## Step 6: Wrap up

1. Show me that `./gradlew check` passes.
2. Confirm that no document includes a snippet a contract test wrote, and tell me where published documentation should come from: documentation tests, on the generated classes, with meaningful values.
3. Tell me which files to commit, and list every finding with its fix.
4. Offer to add a section like this to `AGENTS.md`, and write it only if I agree:

   ```markdown
   ## Contract tests
   - Every response the contract can express is a generated contract test; hand-write one only for a response that depends on behaviour (409, 422, ...), on the same interface-and-hook pattern.
   - Generated stateless tests are not independent: read the first failing one first, fix, and re-run on fresh state.
   - arrangeState sets exactly the state its case needs. Never edit generated tests or stubs.
   - Contract test snippets are never published; documentation comes from documentation tests.
   - Running a Gradle build that resolves plugins needs network access.
   ```
