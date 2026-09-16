# Adopt the API-Only Publisher in a project that owns its API contract

Help me set up the API-Only Publisher in this project. The project implements an API, and that API's description (OpenAPI and/or AsyncAPI) lives in this same project. The goal is that every ordinary build turns the description into one bundled, linted document and uses that document directly. There is no package registry, no publishing, and no Subscriber plugin.

Reference manual (read it if you can reach the web; the facts below are enough if you cannot):
https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/docs/in-the-implementation-project.adoc

## Principles

- **Build only what this project needs today.** Do not add a registry, `channels`, `pack`/`publish`, versioned archives, or the Subscriber plugin. The build reads the document straight from the build directory.
- **Keep tomorrow's change small.** Everything that needs the document reads it through **one** Gradle provider. If the contract is published later, that one line changes.
- Never subscribe this project to its own contract.
- If another project in this repository also needs this contract, stop and tell me. That is a library serving several projects (manual 2), not this setup.
- Keep generated files out of `src/`: put `build.staging` and `build.dist` under `build/`.
- If the contract is a single hand-written file with no `$ref` to other files and no `{{placeholders}}`, tell me. I may not need the Publisher yet.

## Facts: API-Only Publisher (do not invent options beyond these)

- npm package `@arc-e-tect/api-only-publisher`, pinned exactly to `0.0.2`. Requires Node 22+ and npm 10+.
- Run it **only** through an npm script: `"scripts": { "apionly": "api-only-publisher" }`, invoked as `npm run apionly -- <command>`. Use `npm run --silent apionly -- ...` when output is captured or the call is made from Gradle. **Never run `npx api-only-publisher`**: that unscoped name is not this package, and npx would resolve it against the public registry. The one exception is the one-time scaffold, `npx @arc-e-tect/api-only-publisher@0.0.2 init <dir>`.
- Configuration file: `apionly.yaml`, with `schemaVersion: 1`. The keys are `sources.root`, `sources.openapi`, `sources.asyncapi`; `defaults.openapi.lint`, `defaults.openapi.outputName`, `defaults.asyncapi.outputName`, `defaults.placeholders.strict`; `build.staging`, `build.dist`; `toolchain.redocly` (`"@redocly/cli@2.52.0"`), `toolchain.asyncapi` (`"@asyncapi/cli@6.0.2"`); `targets.<name>.openapi.bundle`, `targets.<name>.asyncapi.bundle`, `targets.<name>.publish`; `channels.*`.
- A bundle path is relative to `<sources.root>/<sources.openapi>` (or `<sources.asyncapi>`). The built document is `<build.dist>/<target>/<outputName>`: one flat file, with every `$ref` resolved.
- Commands: `init [dir]`, `build [--target <t>]... [--version <v>] [--openapi|--asyncapi]`, `lint`, `targets`, `closure`. `-C <dir>` runs the command as if started in `<dir>`.
- `{{token}}` in any YAML file is replaced by the contents of `<token>.md`, searched for under the source root. An unresolved token fails the build.

## Step 1: Investigate, then propose (do not edit anything yet)

Find out and report:

1. The build tool and its version. This prompt assumes Gradle; if the project uses something else, say so and stop.
2. Where the API description lives now, whether it is split into fragments, and whether it uses placeholders.
3. The target name to use, normally the service name.
4. Whether a `package.json` already exists, and which Node and npm versions are installed.
5. What currently uses the document: the API-Only Suite (`apiOnlySuite { rootDocument = ... }`), an OpenAPI generator, the classpath, or nothing.
6. Whether the project sets a version (`./gradlew properties -q | grep '^version:'`).

Then give me a short plan that lists every file you will create or change, and wait for my go-ahead.

## Step 2: Implement

1. Create or extend `package.json` with `"devDependencies": { "@arc-e-tect/api-only-publisher": "0.0.2" }` and `"scripts": { "apionly": "api-only-publisher" }`. Run `npm install`. `package-lock.json` gets committed; add `node_modules/` to `.gitignore`.
2. If there is no fragment layout yet, scaffold one into a temporary directory with `npx @arc-e-tect/api-only-publisher@0.0.2 init <tmp>` and move its `specs/` contents into `src/main/api/`, along with `.redocly.yaml`. If a monolithic document already exists, it can become the bundle root unchanged.
3. Write `apionly.yaml` at the project root, with `sources.root: src/main/api`, `build.staging: build/api-only/staging`, `build.dist: build/api-only/dist`, `defaults.openapi.lint: src/main/api/.redocly.yaml`, `defaults.placeholders.strict: true`, the pinned `toolchain` versions, and one entry under `targets`. No `channels`.
4. In `build.gradle`, use this pattern, adapted to the target name. Keep the comments.

   ```groovy
   def contractDir = layout.buildDirectory.dir('api-only/dist/<target>')

   def buildContract = tasks.register('buildContract', Exec) {
       group = 'api-only'
       description = 'Builds and lints this project\'s API description documents.'
       // Declared so Gradle skips the task when nothing changed.
       inputs.dir(layout.projectDirectory.dir('src/main/api')).withPropertyName('fragments')
       inputs.file(layout.projectDirectory.file('apionly.yaml')).withPropertyName('configuration')
       outputs.dir(contractDir)
       workingDir = projectDir
       commandLine 'npm', 'run', '--silent', 'apionly', '--', 'build', '--version', project.version.toString()
   }

   // The one place this build says where its contract comes from.
   def contract = buildContract.map { contractDir.get().file('openapi.yaml') }

   tasks.named('check') { dependsOn buildContract }
   sourceSets.main.resources.srcDir(buildContract)
   tasks.named('processResources') { dependsOn buildContract }
   ```

   - If the project has no version (`unspecified`), leave out `'--version', project.version.toString()`.
   - Wire every consumer of the document to `contract` (for example `apiOnlySuite { rootDocument = contract }`), never to a string path.
   - For the Kotlin DSL, translate faithfully. On Windows the executable is `npm.cmd`.
5. Add `build/` and `node_modules/` to `.gitignore` if they are missing.

## Step 3: Verify, and show me the output

1. `npm run apionly -- targets` lists the target.
2. `./gradlew check` succeeds, and `build/api-only/dist/<target>/openapi.yaml` exists with no `$ref` pointing to another file.
3. Running `./gradlew check` a second time reports `buildContract` as `UP-TO-DATE`.
4. After a trivial edit to one fragment, `buildContract` runs again. Revert the edit afterwards.
5. If the project uses the configuration cache, run step 2 with `--configuration-cache` as well.

Do not report a step as passing unless you ran it.

## Finally

Summarise what changed and what I should commit. Then offer to add a short section to `CLAUDE.md` or `AGENTS.md`, whichever this repository uses, recording three things: the contract is authored under `src/main/api/` and built by `buildContract`; the Publisher runs only as `npm run apionly -- <command>`; and every consumer of the document reads it through the `contract` provider. Ask before writing it.
