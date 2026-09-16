# Set up a versioned specification library for the projects in this repository

Help me set up this repository so that a specification library directory holds the API descriptions (OpenAPI and/or AsyncAPI), and the other projects in the repository, **each versioned independently**, subscribe to the contract versions they need. The library publishes each target as a versioned archive into a **committed** `published/` directory using the API-Only Publisher's `file` channel. Projects read that directory with the API-Only Subscriber's `file` channel and lock what they fetched. There is no package registry.

Reference manual (read it if you can reach the web; the facts below are enough if you cannot):
https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/docs/elsewhere-in-the-repository.adoc

## Principles

- **Build only what this repository needs today.** Everything is in one checkout, so a committed directory replaces a registry. Do not add a Maven repository, credentials, or `channels.maven`.
- **Projects are versioned independently.** Each pins its own contract version and upgrades when it chooses. Never make a project build against unreleased fragments directly.
- **A published version is permanent.** Never overwrite or delete anything under `published/`. A change is released as a new version.
- `published/` is committed. Never gitignore it.
- In each build file, the published directory's path appears exactly once, and each contract is read through exactly one provider.
- Keep the Publisher's default output names (`openapi.yaml`, `asyncapi.yaml`); the Subscriber's providers look for exactly those.
- Set up the library and **one** project first, show me, then do the rest.

## Facts: API-Only Publisher (do not invent options beyond these)

- npm package `@arc-e-tect/api-only-publisher`, pinned exactly to `0.0.2`. Requires Node 22+ and npm 10+.
- Run it **only** through an npm script: `"scripts": { "apionly": "api-only-publisher" }`, invoked as `npm run apionly -- <command>`. Use `npm run --silent apionly -- ...` whenever the output is captured. **Never run `npx api-only-publisher`**: that unscoped name is not this package, and npx would resolve it against the public registry. The one exception is the one-time scaffold, `npx @arc-e-tect/api-only-publisher@0.0.2 init <dir>`.
- `apionly.yaml` (`schemaVersion: 1`) keys: `sources.root`, `sources.openapi`, `sources.asyncapi`; `defaults.openapi.lint`, `defaults.<kind>.outputName`, `defaults.placeholders.strict`; `build.staging`, `build.dist`; `toolchain.redocly` (`"@redocly/cli@2.52.0"`), `toolchain.asyncapi` (`"@asyncapi/cli@6.0.2"`); `targets.<name>.openapi.bundle` / `.asyncapi.bundle` / `.publish`; `channels.file.directory`.
- A bundle path is relative to `<sources.root>/<sources.<kind>>`.
- Commands: `init`, `build [--target <t>]... [--version <v>]`, `targets` (prints `name  [kinds]`, plus `  (publish: false)` where it applies), `closure`, `changed --since <ref> [--quiet]` (prints one changed target per line), `publish --version <v> [--target <t>]... [--channel file]`.
- `publish --channel file` writes `<directory>/<target>/<version>/<target>-<version>.tgz` plus `manifest.json`, and **overwrites an existing version silently**. It refuses a document whose `info.version` differs from `--version`, so always run `build --version v` before `publish --version v`, with the same `v`.
- A target with `publish: false` is built and linted but never published.

## Facts: API-Only Subscriber (do not invent options beyond these)

- Gradle plugin `com.arc-e-tect.api-only-subscriber`, version `0.0.2`, from the Gradle Plugin Portal. Requires Gradle 8+ and Java 21+.
- Configuration:
  ```groovy
  apiOnlySubscriber {
      channel {
          type = 'file'
          directory = "${rootDir}/../contracts/published"   // reads <directory>/<target>/<version>/<target>-<version>.tgz
      }
      subscribe('<target>') {
          version = '1.0.0'          // required
          // allowPrerelease = false // pre-releases (-rc.1, -SNAPSHOT) are refused unless true
      }
  }
  def contract = apiOnlySubscriber.subscription('<target>').openapi   // provider for openapi.yaml; .asyncapi for asyncapi.yaml
  ```
- Tasks: `fetchApiSpec`, `fetchApiSpec<Target>`, `verifyApiSpec`, `verifyApiSpec<Target>`. `check` depends on `verifyApiSpec`; with the `java` plugin, `processResources` depends on the fetch.
- `apionly.lock` beside the build file is generated and committed. A locked version whose bytes changed is refused.
- **Known limitation in 0.0.2:** with two or more subscriptions and the `java` plugin, `processResources` fails with `Entry manifest.json is a duplicate`. The workaround goes **after** the `apiOnlySubscriber` block:
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

1. Where the API descriptions live now (directory, layout, bundle roots, placeholders), and whether an `apionly.yaml` exists.
2. Every project that uses them: its path; whether it is a separate Gradle build or a subproject; DSL; whether the `java` plugin is applied; and how it versions and tags its own releases (for example a semantic-release `tagFormat`).
3. For each project, which targets it needs, and how it uses the documents today. If committed copies exist in `src/main/resources`, say so, because https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/docs/prompts/6-migrate-checked-in-document.md may be the better starting point.
4. The target names, and the starting version for each.
5. A versioning policy. Offer the committed `versions.properties` (bumped in the same pull request as the change) and, if the repository already uses Conventional Commits with semantic-release, a commit-based alternative.
6. The CI system, and whether a token exists that can push release commits and tags to the main branch.

Give me a plan that lists every file you will create or change, and wait for my go-ahead.

## Step 2: The library

1. If there is no library yet, run `npx @arc-e-tect/api-only-publisher@0.0.2 init <library dir>`. If one exists, run `init` in a temporary directory and copy only `apionly.yaml` and `.redocly.yaml`, with `sources` set to match the existing layout.
2. Create `<library>/package.json` with the pinned devDependency and the `apionly` script, then run `npm install`.
3. In `apionly.yaml`: the targets, the pinned toolchain, `defaults.placeholders.strict: true`, and `channels: { file: { directory: published } }`.
4. Confirm that `published/` is not ignored (`git check-ignore -v <library>/published/x` prints nothing).
5. Create `versions.properties` (or the agreed alternative) and `next-version.sh`, which receives `<target> <last tag>` and prints the version to release. Match the target name exactly (not as a regex), and exit non-zero with a message naming the target when there is no version for it, so `release.sh` stops instead of continuing without a version.
6. Create `release.sh` exactly as follows:
   ```bash
   #!/usr/bin/env bash
   # Publishes every target whose closure changed since that target's last release,
   # into the committed published/ directory. Run from a clean checkout of main.
   set -euo pipefail
   cd "$(dirname "$0")"

   targets="$(npm run --silent apionly -- targets | grep -v 'publish: false' | awk '{print $1}')"

   for target in $targets; do
     last="$(git describe --tags --abbrev=0 --match "${target}-contract-v*" 2>/dev/null || true)"

     # Released before, and nothing it reaches has changed since: nothing to do.
     if [ -n "$last" ] && ! npm run --silent apionly -- changed --since "$last" --quiet | grep -qx "$target"; then
       continue
     fi

     version="$(./next-version.sh "$target" "$last")"

     # A published version is permanent: projects have locked its hashes.
     if [ -e "published/$target/$version" ]; then
       echo "$target $version is already published; refusing to overwrite it" >&2
       exit 1
     fi

     npm run --silent apionly -- build   --version "$version" --target "$target"
     npm run --silent apionly -- publish --version "$version" --target "$target" --channel file

     git add "published/$target/$version"
     git commit -m "chore(contracts): publish $target $version [skip ci]"
     git tag "${target}-contract-v${version}"
   done
   ```
   If the repository's projects already use tags that could match `<target>-contract-v*`, tell me and propose a different tag prefix.
7. Run `npm run apionly -- targets` and `npm run apionly -- build`.
8. **Ask me before running `release.sh`**, because it commits and tags. With my go-ahead, commit the library, run it, and show `git ls-files <library>/published` and the new tags. Do not push.

## Step 3: The first project

1. Apply `id 'com.arc-e-tect.api-only-subscriber' version '0.0.2'`, configure the `file` channel with the real relative path to `published/`, and subscribe at the agreed version.
2. Add one provider per contract and wire every consumer of a document to it. With two or more subscriptions and the `java` plugin, apply the workaround, and report any code that reads the documents from the classpath, since the paths become `contracts/<target>/`.
3. Run `./gradlew fetchApiSpec` and commit `apionly.lock` with the build file.

## Step 4: Verify, and show me the output

1. `./gradlew fetchApiSpec` prints `Subscribed to <target> <version>`; show `apionly.lock`.
2. `./gradlew check` passes (with `--configuration-cache` if the project uses it).
3. Drift detection works: append a comment to `build/api-spec/<target>/openapi.yaml`, run `./gradlew verifyApiSpec`, show the failure, then run `./gradlew fetchApiSpec` to restore the file.
4. The release script is idempotent: with no fragment changes, a second `release.sh` publishes nothing. Run this only with my go-ahead.

Do not report a step as passing unless you ran it. Stop after the first project and wait before wiring the others.

## Step 5: CI (only once every project is wired, and only if I agree)

- Project workflows trigger on their own directory only, and need Java but not Node.
- A pull-request workflow for the library runs `npm ci`, runs `npm run apionly -- build`, and fails when `git diff --name-only --diff-filter=MDR "origin/<base>...HEAD" -- <library>/published` prints anything.
- A release workflow on pushes to main that touch the library checks out with `fetch-depth: 0` and a token allowed to push, sets up Node 22, runs `npm ci`, sets a git identity, runs `release.sh`, and then runs `git push origin HEAD --tags`.

## Finally

Summarise what changed and what I should commit. Then offer to add a short section to `CLAUDE.md` or `AGENTS.md` recording: where the library is; that contract changes need a version bump and are released by `release.sh`; that nothing under `published/` is ever edited; that projects change contract versions in `build.gradle` and run `./gradlew fetchApiSpec`; and that the Publisher runs only as `npm run apionly -- <command>`. Ask before writing it.
