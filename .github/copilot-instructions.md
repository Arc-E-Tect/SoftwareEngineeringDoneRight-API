# Project Overview

This is a public repository for API-related software: the API-Only Publisher (Node.js, npm), and the API-Only Subscriber and TranscriberJ (Gradle plugins, Java 21). Each component builds, tests and releases on its own, as described below.

## Security: zero credentials policy

No passwords, secrets, private keys, tokens, credentials, or sensitive production data may be committed to version control, including test credentials.

- Use environment variables or a deployment secret manager for all secrets.
- Use placeholders such as `${API_TOKEN}` or `<API_TOKEN_DESCRIPTION>` in documentation and configuration templates.
- Never assign secret-like configuration values literal defaults, including in tests.
- Keep `.env` files local and ignored. Only sanitized `.env.example`, `.env.sample`, or `.env.template` files may be committed.
- Never disclose a credential in source, documentation, logs, issues, pull requests, or generated artifacts.
- Store CI/CD secrets in GitHub Actions secrets. When a deployment platform is selected, use its managed secret store and short-lived identity mechanisms where available.

When a new secret is required:

1. Document its name and purpose in a sanitized example file.
2. Read it through an environment variable or a secret-manager integration.
3. Add the relevant local file pattern to `.gitignore`.
4. Verify the staged-content checks in `.githooks/pre-commit` reject a hardcoded value.
5. Update `SECURITY.md` or other operational documentation when the handling policy changes.

## Git hooks

Install the repository hooks before contributing:

```bash
./scripts/setup-hooks.sh
```

The hooks provide local defense in depth by rejecting keystores and likely hardcoded secrets, linting modified GitHub Actions workflows, and preventing pushes to branches with closed or merged pull requests. They do not replace server-side controls and must not be bypassed except for a documented, reviewed reason.

## GitHub Actions

- Keep the default `GITHUB_TOKEN` read-only. Every workflow and job must declare only the permissions it needs.
- All external actions in `uses:` must be pinned to a full 40-character commit SHA. Include a version comment beside the SHA when practical.
- Use only GitHub-owned or verified-creator actions unless the repository's allowed-actions policy is deliberately extended and documented.
- Run `actionlint` after changing a workflow, and fix all reported errors before committing.
- Do not use `pull_request_target` with untrusted pull-request code. Do not expose secrets to pull-request workflows from forks.
- Use immutable action, container, and dependency versions. Do not use mutable `latest` tags.

## API-Only Publisher

- Build and test with `cd api-only-publisher && npm ci && npm test`. `npm test` gates on 90% of lines, 90% of functions and 80% of branches, with no exclusions; `test:quick` skips the gate. `build.e2e.test.js` runs the real bundlers, so it needs network access.
- Its one runtime dependency is `yaml`. Add none without an explicit decision.
- It is built by `api-only-publisher-build.yml` on every push to a branch other than `main`, released by `api-only-publisher-release.yml` under the `api-only-publisher-v*` tags, and published to npm by trusted publishing: npm binds the trusted publisher to that workflow's file name, so do not rename it or move the publish step to another workflow.
- Behaviour that changes what a library sees -- a message, a configuration key, a check -- is documented in `api-only-publisher/README.adoc` and, with its reasoning, in `docs/reference/authoring-a-specification-library.adoc`, in the same change.

## API-Only Subscriber

- Build and test with `cd api-only-subscriber && ./gradlew build`.
- It is built by `api-only-subscriber-build.yml` and released by `api-only-subscriber-release.yml` under the `api-only-subscriber-v*` tags.

## API-Only TranscriberJ

- Build and test with `cd api-only-transcriberj && ./gradlew check`. Add `-PreferenceRepository=<SoftwareEngineeringDoneRight-Code checkout>` to also compare the committed copies of the reference implementation's hand-written classes with that repository.
- The `model` and `spi` packages are what emitter libraries compile against; changing them changes a published interface. They are part of the plugin artifact and released with it.
- The plugin's runtime dependencies end up on every consumer's build script classpath. They are the API-Only Subscriber, `snakeyaml-engine` and `dsl-updater-core` (for `updateApiOnlyTranscriberJDSL`); add none without an explicit decision.
- The plugin applies the Subscriber it depends on, so a project that applies only the TranscriberJ gets the Subscriber that `api-only-subscriber` in `api-only-transcriberj/gradle/libs.versions.toml` names. Keep that pin at the latest Subscriber release: after each release, the `Update-TranscriberJ` job in `api-only-subscriber-release.yml` opens a `fix(api-only-transcriberj)` pull request that moves it, and merging that releases the TranscriberJ with it. Do not move the pin by hand in an unrelated change.
- It is built by `api-only-transcriberj-build.yml`, released by `api-only-transcriberj-release.yml` under the `api-only-transcriberj-v*` tags, and scanned by `nvd-cache-refresh.yml`.

## Contribution and change rules

- Work on a branch and use a pull request for changes to `main`. Do not bypass the repository ruleset.
- Before pushing an existing branch, check that its pull request is still open. Do not push additional commits to a merged or closed pull-request branch.
- Keep public documentation, security policy, and configuration examples aligned with behavioral or operational changes.
- When an implementation stack is introduced, add its build/test instructions, dependency policy, and targeted CI validation to this document in the same change set.
- Validate only with the project's existing tools. Do not introduce a build, release, or dependency-management system without an explicit decision.
