# Take the next step with a contract: publish it further than it goes today

This repository already uses the API-Only Publisher in one of two ways:

- **A project builds its own contract** and reads the document directly (manual 1), or
- **a specification library in this repository** publishes versioned archives into a committed `published/` directory, and projects subscribe through the `file` channel (manual 2).

Something now needs the contract to travel further. Help me make that change, and only as much of it as the need requires.

Reference manuals (read them if you can reach the web; the facts below are enough if you cannot):
- https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/docs/in-the-implementation-project.adoc (section "When tomorrow comes")
- https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/docs/elsewhere-in-the-repository.adoc (section "When tomorrow comes")

## Principles

- **Match the mechanism to the distance.** A consumer in this repository needs a versioned archive in a committed directory. A consumer outside this repository needs a registry. Add nothing beyond what the new consumer requires.
- A project never subscribes to a contract it builds itself.
- Projects that are not affected do not change. In particular, projects already subscribing through the `file` channel keep doing so until the library itself leaves the repository.
- When a project switches from the `file` channel to `maven`, the version stays the same, so `apionly.lock` must change by exactly one line per target (`channel file` becomes `channel maven`). Any other change to the lockfile means the bytes differ: stop and report it.
- Credentials come only from the environment: the variable named by `channels.maven.tokenEnv` for the Publisher, Gradle properties for consumers. Never write a credential anywhere.
- Change one project per commit.

## Facts: API-Only Publisher (do not invent options beyond these)

- It runs **only** as `npm run apionly -- <command>` (npm script `"apionly": "api-only-publisher"`, package `@arc-e-tect/api-only-publisher` pinned to `0.0.2`). Use `npm run --silent apionly -- ...` when output is captured. **Never run `npx api-only-publisher`**.
- `channels.file.directory`: `publish --channel file` writes `<directory>/<target>/<version>/<target>-<version>.tgz` plus `manifest.json`.
- `channels.maven`: `groupId` (required), `repository` (a filesystem path writes a Maven layout to disk; an `http(s)` URL deploys with a bearer token from the environment variable named by `tokenEnv`, default `MAVEN_TOKEN`), and optionally `artifactId` and `extension` (default `tgz`).
- `publish --version <v> [--target <t>]...` with no `--channel` ships the **same packed bytes** to every configured channel. `--channel <c>` restricts it.
- Always run `build --version v` before `publish --version v`, with the same `v`. `publish` refuses a document whose `info.version` differs.

## Facts: API-Only Subscriber (do not invent options beyond these)

- Gradle plugin `com.arc-e-tect.api-only-subscriber`, version `0.0.2`.
- `channel { type = 'file'; directory = '...' }` or `channel { type = 'maven'; groupId = '...' }`, plus `subscribe('<target>') { version = '...' }`. `maven` resolves `groupId:artifactId:version@tgz` through `repositories`, and a named repository with `credentials(PasswordCredentials)` reads `<name>Username` and `<name>Password` from Gradle properties.
- Tasks: `fetchApiSpec`, `verifyApiSpec` (part of `check`). `apionly.lock` is generated and committed, and records `target`, `version`, `channel` and a `sha256` line per document.
- Read documents through `apiOnlySubscriber.subscription('<target>').openapi`.
- **Known limitation in 0.0.2:** with two or more subscriptions and the `java` plugin, `processResources` fails with `Entry manifest.json is a duplicate`. Workaround, placed after the `apiOnlySubscriber` block:
  ```groovy
  sourceSets.main.resources.setSrcDirs(['src/main/resources'])
  tasks.named('processResources') {
      ['<target-a>', '<target-b>'].each { target ->
          from(apiOnlySubscriber.subscription(target).into) { into "contracts/$target" }
      }
  }
  ```

## Step 1: Investigate, then propose (do not edit anything yet)

Report:

1. Which of the two setups this repository uses, where the contract or library lives, and its targets.
2. Every project that uses a contract today, and how: direct build task, `file` subscription, and at which versions.
3. The new need: which contract, and whether the consumer is **in this repository** or **outside it**. Ask me if this is unclear.
4. For a consumer outside the repository: the Maven repository URL and group id to publish to. Ask me for anything you cannot find.
5. The existing release job or `release.sh`, and the CI system.

Then propose the path below that fits, and wait for my go-ahead.

## Path A: manual 1, a new consumer inside this repository

Several projects now depend on the contract, and each has its own version, so the contract belongs in a library of its own. **Stop and tell me.** The right next step is setting up a library as in manual 2 (https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/docs/prompts/2-contract-library-in-repository.md), which moves the fragments out of this project. Do not bolt a `published/` directory onto the implementation project.

## Path B: manual 1, a new consumer outside this repository

1. Add `channels.maven` with `groupId`, `repository` and `tokenEnv` to the project's `apionly.yaml`.
2. Try it locally: temporarily set `repository` to `build/api-only/maven`, run `npm run apionly -- build --version <v>` and then `npm run apionly -- publish --version <v>`, list the files written, and restore the URL.
3. In the project's existing release job, once the version is known, run `build --version "$VERSION"` and then `publish --version "$VERSION"`, with the token in the variable named by `tokenEnv`.
4. The project itself does not subscribe. Point me to https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/docs/prompts/4-subscribe-to-published-contract.md for the external consumer.

## Path C: manual 2, a new consumer outside this repository

1. Add `channels.maven` next to `channels.file` in the library's `apionly.yaml`.
2. Try it locally: temporarily set `repository` to a directory, then for one existing released version run `build --version <v> --target <t>` and `publish --version <v> --target <t> --channel maven`. List the files and restore the URL. Do not publish to `file` here, because that version already exists under `published/`.
3. In `release.sh`, remove `--channel file` from the `publish` line, so each new release goes to both channels with identical bytes. Keep everything else.
4. Add the token to the release workflow's environment under the variable named by `tokenEnv`.
5. Projects in this repository do not change. Point me to prompt 4 (link above) for the external consumer.

Note that versions released before this change exist only in `published/`. If the external consumer needs one of them, ask me before republishing it to the registry, and never republish it to `file`.

## Path D: manual 2, the library moves to its own repository

Do Path C first, so every version the projects use exists in the registry. Then, for each project, one commit at a time:

1. Declare the named repository with `credentials(PasswordCredentials)`.
2. Change the channel block from `type = 'file'` / `directory` to `type = 'maven'` / `groupId`. Keep every `subscribe` version and every provider line unchanged.
3. Run `./gradlew fetchApiSpec` and show `git diff apionly.lock`. It must show exactly one changed line per target: `channel file` becoming `channel maven`. If any version or `sha256` line changes, stop and report it.
4. Run `./gradlew clean check`, show the output, and commit the build file together with `apionly.lock`.

Wait for me before moving to the next project. Splitting the library into its own repository (`npm run apionly -- split --out build/split --by target`) is a separate step; describe it, but do not do it unless I ask.

## Verify

Do not report a step as passing unless you ran it. For every path, show the output of the local publish, and for Path D, show the lockfile diff for each project.

## Finally

Summarise what changed. List the secrets and Gradle properties that need to be set, by name only. List any projects that still use the `file` channel or build their contract directly, and confirm with me that this is intended. Then offer to update `CLAUDE.md` or `AGENTS.md` accordingly, and ask before writing.
