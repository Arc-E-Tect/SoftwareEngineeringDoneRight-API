---
description: Write the contract tests the API-Only TranscriberJ cannot generate, on top of the classes it does, test first.
---

# Write the contract tests the generator cannot write, on top of the classes it does

Help me write contract tests for this Gradle project, which generates classes from its API contract with the API-Only TranscriberJ, where the generated classes do not cover everything: a method the generator could not write, or a response whose trigger the schema cannot express, such as a 422 from a business rule.

When we are done:

- every response each operation in scope lists has one contract test, checking status, content type and fields against the generated classes;
- whatever the generator could not write is written by hand from the generated pieces, and fails a test when the contract changes underneath it;
- every other way a request can be invalid, and every business rule, is tested as behaviour, not as a contract test;
- `./gradlew check` passes.

## How to work

- Start in plan mode.
  Investigate first, then present a plan that lists every file you will create or change, and wait for my approval before you edit anything.
- Keep a todo list of the steps below, and update it as you finish each one.
- Work test first.
  Create each failing check before the change that makes it pass, run it, and show me the failure.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- Do not commit, push, or change anything outside this project.
  At the end, tell me what to commit.
- Do not invent generated classes, constants or methods.
  Read what was generated, under `build/generated/sources/transcriberj/`, and use only what is there.
  If you need something that is not, stop and ask me.

## Principles

- **A contract test checks the shape of a response, not the behaviour that produced it.**
  One contract test per response the contract lists: one example request is enough for each.
  Every other way to be invalid, and every business rule, is a behaviour test, written as scenarios against the service.
  Do not write one contract test per invalid value.
- **Prefer fixing the contract.** Where the generation report gives a remedy -- name the branches of a `oneOf`, make an inline schema a component -- tell me before writing anything by hand: fixing the contract gives every consumer the generated code.
  Write it by hand only when I say the contract cannot change.
- **Write by hand only what could not be generated**, and build it from the generated pieces: constants, constraints, `ContractJson`.
  A literal where a generated constant exists is not allowed.
- **Guard what is hand-written**, so that it fails a test when the contract changes and when the generator can take over.
- **Generated code is for tests.** Never add `main` to a subscription's `sourceSets`, and never make application code import a generated class.
- **Never edit generated sources.**

## Versions

Wherever one of these names appears below, in braces, it stands for this version:

| Name | Version |
|---|---|
| `{api-only-transcriberj-version}` | `0.3.3` |
| `{api-only-transcriberj-restdocs-version}` | `0.1.1` |

These are the versions this prompt was verified with. Use them unless I name newer ones; a newer release works the same unless its changelog says otherwise.

## Facts: API-Only TranscriberJ {api-only-transcriberj-version}

- The generated tree is in `build/generated/sources/transcriberj/<contract>/`, in the subscription's `basePackage`, compiled by the source sets in `sourceSets` (`test` by default).
- `build/reports/transcriberj/<contract>.txt` lists every *degraded* method -- one generated to throw `UnsupportedOperationException` because a construct cannot be represented -- with the construct, its location and, where there is one, the remedy.
  A degraded `body(...)` takes `Object...`.
- What a class holds, whether or not a method degraded:
  - provenance: `FRAGMENT_PATH` and `FRAGMENT_SHA256` (SHA-256 of the fragment as bundled), or `LOCATION` and `SCHEMA_SHA256` for an inline schema; `CONTRACT_VERSION`;
  - constraints as constants: `MINIMUM`, `MAXIMUM`, `MIN_LENGTH`, `MAX_LENGTH`, `PATTERN`, `FORMAT`, `ENUM` and the rest; an object's are prefixed with the property, e.g. `EMAIL_ADDRESS_MIN_LENGTH`;
  - `description()`; `body(...)` and `fields(prefix)` for an object; `OPEN`.
- An operation's class `<OperationId>Operation`: `OPERATION_ID`, `METHOD`, `PATH`, `REQUEST_CONTENT_TYPE` where there is a body, `STATUS_<code>` and `CONTENT_TYPE_<code>` per response, and `path(...)` filling in the placeholders.
- **Constraints are constants, not checks**: a generated `body(...)` writes whatever it is given.
- **Field descriptions are a projection** (path, type, optional, description): a response that matches them has the fields the contract describes, which is not schema validity.
- **Visibility**: a class is public when code outside the package needs it (a body, a parameter's schema, a non-schema component, what a public `body(...)` takes, a branch of a `oneOf`/`anyOf` a public body is).
  Everything else is package-private, including scalar components such as a constrained string or integer, and `ContractJson`.
  Hand-written code that needs them goes **in the generated package, in the test source set** (`src/test/java/<basePackage path>/`), next to the generated classes.
  This is a design decision of the TranscriberJ, not a workaround.
- **The package-private members are not a published API.** They are regenerated every build and may change when the TranscriberJ is upgraded; hand-written code using them then stops compiling and must be adjusted.
- **Generated nowhere, so typed by hand and owned by the project**: the members of a schema whose `body(...)` degraded (names, which are required, nesting); `const` values (private in named components, absent for inline branches); constraints written inline inside an inline branch.
  Nothing verifies hand-written code against the contract: `verifyContractSources<Contract>` checks only the generated tree.
- `ContractJson` (package-private): `string(value)` quotes a string, `member(json, name, value)` appends a member to a `StringBuilder` started with `"{"`, `close(json)` ends it with `}` and a newline, `embed(body)` nests a body another `body(...)` returned, and `array(values)` joins values already written as JSON -- the same way generated bodies write.
- A `oneOf`/`anyOf` with an inline object branch degrades `body(...)` and `fields(...)`; remedy: name the branches.
  A choice whose branches are all references has no `body(...)` of its own; each branch has one.

## Facts: the REST Docs emitter {api-only-transcriberj-restdocs-version}

- `<Class>Docs.responseFields()` and `requestFields()`, in `<basePackage>.restdocs`, for every public class with a body; used with `document(...)`, they fail when a documented required field is missing or an undocumented one is present.
- A companion method whose core method degraded throws too.

## Step 1: Investigate and propose

Do not edit anything in this step.
Find out and report:

1. The TranscriberJ version, the subscription's `basePackage` and `sourceSets`, and whether the REST Docs emitter is applied.
   If the version is older than `{api-only-transcriberj-version}`, stop and tell me.
2. Every degraded method in `build/reports/transcriberj/<contract>.txt`, and its remedy.
3. For each operation in scope: the responses it lists, and which of them the schema cannot fully explain (a 422 from a business rule, say).
4. The web framework and how the project's tests call it (MockMvc, WebTestClient, a running server).
5. Existing contract tests and behaviour tests, and where they are.

Then present the plan: which remedies to propose to the contract's owners, what to write by hand and in which file, the contract tests (one per response), and the behaviour tests.
Wait for my approval.

## Step 2: The failing tests

1. For each hand-written part, a guard test in the generated package:
   - the class's `FRAGMENT_SHA256` equals a `WRITTEN_AGAINST` constant in the hand-written class, with a message naming the fragment and saying to check the hand-written class and update the constant;
   - the degraded method still throws `UnsupportedOperationException`, with a comment saying that when it no longer does, the hand-written part goes;
   - every sample value satisfies the generated constraints.
2. One contract test per response, using the operation's `PATH`, `REQUEST_CONTENT_TYPE`, `STATUS_<code>` and `CONTENT_TYPE_<code>`, and the response's `<Class>Docs.responseFields()`.
   Each uses one example request.
3. Behaviour tests for the rules and the invalid inputs, as scenarios; parameterised where the scenarios differ only in data.

Run them and show me that they fail, and why.

## Step 3: Make them pass

1. Write the hand-written body (or other missing part) in the generated package, in the test source set, using the generated constants and `ContractJson`; type only what no generated class holds, such as the member names of an inline branch.
2. Set `WRITTEN_AGAINST` to the current `FRAGMENT_SHA256`.
3. Change the service only where a test shows it does not do what the contract promises, and tell me before you do.

Run `./gradlew check` and show me that it passes.

## Step 4: Prove the guards work

Show me each of these on a copy, or undo it afterwards:

1. Remove a constraint the hand-written code uses from the contract and raise the version: the tests no longer compile.
2. Rename a member of the schema the hand-written code types, and raise the version: only the `WRITTEN_AGAINST` guard fails, while the contract tests still pass -- which is why the guard exists.

## Step 5: Wrap up

1. Tell me which files to commit, and which remedies to take to the contract's owners.
2. List, per hand-written class, every value it types that no generated class holds, and say plainly that keeping those in line with the contract is now this project's responsibility, guarded only by the `WRITTEN_AGAINST` test.
3. Offer to add a section like this to `CLAUDE.md`, and write it only if I agree:

   ```markdown
   ## Contract tests
   - One contract test per response an operation lists; it checks status, content type and fields with the generated classes. Invalid inputs and business rules are behaviour tests.
   - What the TranscriberJ cannot generate is written by hand in the generated package under src/test/java, from generated constants, and pinned to its fragment's FRAGMENT_SHA256.
   - A failing WRITTEN_AGAINST guard means the contract changed: check the hand-written code against it, then update the constant.
   ```
