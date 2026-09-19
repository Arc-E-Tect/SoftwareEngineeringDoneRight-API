# Project Overview

This is a public repository for API-related software. The implementation language, build system, runtime, and release process are intentionally not defined yet.

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
