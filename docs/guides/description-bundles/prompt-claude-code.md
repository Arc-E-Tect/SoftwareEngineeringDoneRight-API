---
description: Give the classes the API-Only TranscriberJ generates descriptions this project owns, in one language or several, test first.
---

# Give the generated contract classes descriptions this project owns, test first

Help me add a description bundle to this Gradle project, which generates classes from an API contract with the API-Only TranscriberJ.
The descriptions those classes carry, and that generated documentation shows, should come from a `ResourceBundle` this project writes, in the languages I name, and fall back to what the TranscriberJ generates wherever the bundle has no text.

When we are done, `./gradlew test`:

- checks that each class and field the bundle covers is described with the bundle's text, in every language it has a file for;
- checks that what the bundle does not cover falls back, first to the base file and then to the generated text;
- checks that a run documents in the language the build asks for, English unless told otherwise;
- fails on a key in the bundle that the generated classes no longer ask for.

## How to work

- Start in plan mode.
  Investigate first, then present a plan that lists every file you will create or change, and wait for my approval before you edit anything.
- Keep a todo list of the steps below, and update it as you finish each one.
- Work test first.
  Create each failing check before the change that makes it pass, run it, and show me the failure.
- Report a check as passing only after you ran it and saw it pass, and show the relevant output.
- Do not commit, push, or change anything outside this project.
  At the end, tell me what to commit.
- Do not invent configuration keys, methods, system properties or tasks.
  Everything that exists is listed under the facts below.
  If you need something that is not listed, stop and ask me.

## Principles

- **The bundle is an overlay.** It carries only the text this project wants to differ from the generated text; everything else falls back.
  Do not copy the contract's descriptions into it.
- **Keys come from the generated code, never from memory.** Read them from `ContractDescriptions.keys()` or the `KEYS` list in the generated `ContractDescriptions.java`.
- **Generated code is for tests.** The generated classes, and so the bundle files, belong to the source sets the TranscriberJ generates into, `test` by default.
  Do not move either to `main`, and do not change application code to use the generated classes.
- **Never edit generated sources** under `build/generated/sources/transcriberj/`.
- **Do not change the contract** to change a description.
  If a description is wrong in the contract, tell me; the contract's owners fix it there.
- **If the contract's own descriptions are what readers should see, in one language, stop and tell me.**
  `generateDocs = true` alone does that, and a bundle would only add text to maintain.

## Versions

Wherever one of these names appears below, in braces, it stands for this version:

| Name | Version |
|---|---|
| `{api-only-transcriberj-version}` | `0.3.2` |
| `{api-only-transcriberj-restdocs-version}` | `0.1.1` |

## Facts: API-Only TranscriberJ {api-only-transcriberj-version}

- Gradle plugin `id 'com.arc-e-tect.api-only-transcriberj' version '{api-only-transcriberj-version}'`.
  It needs Java 21 or newer, and a contract the API-Only Subscriber fetches.
- Configuration, per subscription:

  ```groovy
  apiOnlyTranscriberJ {
      subscription('<target>') {
          basePackage = 'com.example.contract'   // required
          generateDocs = true                     // descriptions from the contract; default false
          descriptionBundle = 'docs.Descriptions' // a ResourceBundle base name; default none
      }
  }
  ```

- **Where a description comes from**, first match wins, at the moment it is asked for:
  1. the locale's own file, e.g. `docs/Descriptions_nl_BE.properties`;
  2. its parents' files, e.g. `docs/Descriptions_nl.properties`;
  3. the base file, `docs/Descriptions.properties`;
  4. what generation produced: the contract's text with `generateDocs = true`, otherwise the placeholder `INTENTIONALLY LEFT BLANK - WILL BE PROVIDED AT A LATER STAGE`.

  A missing file, a missing key and a blank entry all fall through.
  The JVM's default locale is never part of the chain.
  Resolving never throws, and a description is never empty.
- **Keys.** A class's key is its class name, e.g. `OrderV1`.
  A field's key is `<ClassName>.<path>`, with the path exactly as the field reports it: `OrderV1.id`, `OrderV1.customer.address.street` for a nested object, `OrderV1.lines[].sku` for a field of an array's items.
  A field whose type is another generated class is described under that class's key.
- **Generated API**, in the subscription's `basePackage`:
  - on every generated class: `description()`, `description(Locale)`, `fields(String prefix)`, `fields(String prefix, Locale)`; a `ContractField` has `path()` and `description()`;
  - `ContractDescriptions.keys()`: every key the tree asks for;
  - `ContractDescriptions.missing()` and `missing(Locale)`: the keys no file in the chain covers, so they show the generated text;
  - `ContractDescriptions.untranslated(Locale)`: the keys that locale's own `<bundle>_<language>.properties` has no text for;
  - `ContractDescriptions.locale()`: the locale a call without one resolves in;
  - `ContractDescriptions.LOCALE_PROPERTY` = `apionly.descriptions.locale` and `ContractDescriptions.BUNDLE_PROPERTY` = `apionly.descriptions.bundle`: system properties, read in the JVM that runs the generated code, that set the locale and the bundle for a run.
    Without the locale property, `locale()` is the JVM's default locale.
- The bundle is read at run time; changing a properties file needs no regeneration.
  Without `descriptionBundle`, the generated classes are exactly what they were before bundles existed.
- Tasks: `generateContractSources<Target>` generates the tree into `build/generated/sources/transcriberj/<target>/` and writes `build/reports/transcriberj/<target>.txt`, whose Notes name the bundle and the number of keys it may carry.
  A class or field without a description, with `generateDocs = true`, is a recommendation in that report.
- Properties files are read as UTF-8.

## Facts: the REST Docs emitter {api-only-transcriberj-restdocs-version}

- Added with `transcriberjEmitters 'com.arc-e-tect:api-only-transcriberj-restdocs:{api-only-transcriberj-restdocs-version}'`, it generates `<Class>Docs` in `<basePackage>.restdocs`.
- `<Class>Docs.fields()` and `fields(String prefix)` have **no locale parameter**: they resolve in `ContractDescriptions.locale()`, so the language follows `apionly.descriptions.locale`.

## Step 1: Investigate and propose

Do not edit anything in this step.
Find out and report:

1. The TranscriberJ version, and each subscription's `basePackage`, `generateDocs` and `sourceSets`.
   If the version is older than `{api-only-transcriberj-version}`, stop and tell me.
2. Whether anything renders documentation from the generated classes (the REST Docs emitter, or code of this project), and in which test source set.
3. The test framework and assertion library.
4. Which languages I want, and which is the base language; ask me if nothing says.
5. Who writes the text, and which classes and fields they want to reword; ask me.
6. Run `./gradlew generateContractSources<Target>` and list `ContractDescriptions.keys()` from the generated `ContractDescriptions.java`.

Then present the plan: the bundle name (`docs.Descriptions` unless the project has a convention), the files, the keys each will carry, and the tests.
Wait for my approval.

## Step 2: The failing tests

In the test source set the TranscriberJ generates into, create a test class with:

1. **The bundle wins, the rest falls back**: for two or three keys in the base file, `description(Locale)` or the field's description in `fields("", locale)` equals the bundle's text; for one key not in it, it equals the generated text.
2. **Each other language**: the same for that language's file, plus one key only the base file has (it gets the base text) and one key in no file (it gets the generated text).
3. **The run's language**: `description()` without a locale equals the text for `ContractDescriptions.locale()`.
4. **What is not covered yet**: `missing(Locale)` contains a key no file has and not one the base file has; `untranslated(Locale)` contains a key the language's file lacks.
5. **No orphaned keys**: load each properties file as UTF-8 with `java.util.Properties`, remove `ContractDescriptions.keys()` from its keys, and assert that nothing is left, reporting every file's leftovers in one failure.

A test that sets `apionly.descriptions.locale` itself restores the value the run started with afterwards, clearing it if there was none.

Run the tests and show me that they fail, and why.

## Step 3: Configure and write the bundle

1. Set `descriptionBundle` on the subscription.
   Keep `generateDocs = true` unless I say the placeholder is the fallback I want.
2. Create the base file, `src/test/resources/docs/Descriptions.properties` (or the resources of the source set from step 1), with a comment explaining the key scheme, and only the keys we agreed.
3. Create one file per other language, `Descriptions_<language>.properties`.
4. Pass the run's language to the test JVM, pinned to the base language when nothing is asked:

   ```groovy
   tasks.named('test') {
       // The language this run documents in, English unless asked: ./gradlew test -PdocsLocale=nl
       systemProperty 'apionly.descriptions.locale', providers.gradleProperty('docsLocale').getOrElse('en')
   }
   ```

   Apply it to every test task that renders documentation.
   A `-D` on Gradle's command line does **not** reach the test JVM.

Run `./gradlew test`, and `./gradlew test -PdocsLocale=<language>` for each other language, and show me that everything passes.

## Step 4: Prove the guards work

Show me each of these, then undo it:

1. Add a key to a language file that a test expects to fall back; the fallback test and `untranslated` test fail.
2. Add a key with a typo to the base file; the orphan test fails and names it.

## Step 5: Wrap up

1. Tell me which files to commit.
2. Tell me what to do when the contract changes:
   - a new field has no key: it falls back, the report recommends describing it if the contract does not, and `missing()` lists it;
   - a renamed schema or field, or one moved into a shared schema, leaves its old keys behind: the orphan test lists them, and they are renamed in every file in the same change as the contract version.
3. Offer to add a section like this to `CLAUDE.md`, and write it only if I agree:

   ```markdown
   ## Contract descriptions
   - Descriptions of the generated contract classes come from `docs/Descriptions*.properties`; keys are `<ClassName>` and `<ClassName>.<path>`, listed in the generated `ContractDescriptions.keys()`.
   - Take keys from the generated code, never type them from memory; the orphan test fails on a key nothing asks for.
   - Rename bundle keys in the same change that renames a schema or field.
   - Render another language with `./gradlew test -PdocsLocale=<language>`.
   ```
