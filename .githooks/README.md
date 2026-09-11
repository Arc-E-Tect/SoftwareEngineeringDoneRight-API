# Git Hooks

The repository hooks are a local, defense-in-depth layer. GitHub branch rules, secret push protection, and Actions restrictions remain the authoritative controls.

## Setup

Run this once after cloning:

```bash
./scripts/setup-hooks.sh
```

This sets the repository-local `core.hooksPath` to `.githooks` and verifies the hooks are executable.

## Hooks

### pre-commit

- Blocks keystore files (`.p12`, `.pfx`, `.jks`, and `.keystore`).
- Checks staged text files for likely hardcoded secret assignments.
- Runs `actionlint` on staged GitHub Actions workflow files.

The secret check allows documentation in `SECURITY`, `POLICY`, and `SECRET` files, sanitized example/template files, the hook source, and the Copilot instructions. It is not a substitute for GitHub secret scanning.

### pre-push

Checks a feature branch's pull-request state with GitHub CLI and blocks a push when its pull request is already closed or merged.

## Dependencies

- Git
- [GitHub CLI](https://cli.github.com/) for the pull-request state check
- [actionlint](https://github.com/rhysd/actionlint) when committing a workflow

## Bypassing hooks

`git commit --no-verify` and `git push --no-verify` bypass local hooks. Use them only for an explicit, documented, reviewed exception. They do not bypass GitHub server-side protection.
