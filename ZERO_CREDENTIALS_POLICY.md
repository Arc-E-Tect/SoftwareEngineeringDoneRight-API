# Zero Credentials Policy

No credentials of any kind are committed to this repository.
That covers passwords, API keys, tokens, private keys, keystores and signing keys, in source code, build scripts, configuration, test resources, documentation and workflow files.
Test credentials are no exception: a weak test password that is committed once is public forever, and gets reused.

## Where credentials live

- **GitHub Actions:** in GitHub Actions secrets.
  The credentials for the Gradle Plugin Portal and Maven Central, and the signing keys, live in the `release` environment, which only jobs running on `main` can use.
- **Local development:** in environment variables, or in a local `.env` file.
  `.env` and `.env.*` are ignored by Git; only sanitized `.env.example`, `.env.sample` and `.env.template` files may be committed.
- **Deployed software:** in the platform's own secret store, injected at run time.

## How code refers to a credential

A credential is always read at run time, by name, without a default value.

- In configuration files: `${SECRET_NAME}`.
- In Gradle build scripts: `System.getenv('SECRET_NAME')` or `providers.environmentVariable('SECRET_NAME')`.
- In workflows: `${{ secrets.SECRET_NAME }}`, passed to the step that needs it and no further.

A default value turns a missing credential into a working build with a known password, so build scripts never supply one.

## What documentation may show

Documentation shows the name of a credential, never a value:

- placeholders such as `<SECRET_NAME_DESCRIPTION>` or `${SECRET_NAME}`;
- example files with the `.example`, `.sample` or `.template` extension.

## Adding a credential

1. Document its name and purpose in a sanitized example file or in the README of the project that needs it.
2. Read it through an environment variable or the platform's secret store.
3. Add any local file that holds it to `.gitignore`.
4. Store the value in GitHub Actions secrets, in the `release` environment when it publishes or signs a release.

## Enforcement

- The `pre-commit` hook in `.githooks/` rejects keystores and likely hardcoded secrets; install it with `./scripts/setup-hooks.sh`.
- GitHub secret scanning and push protection reject known secret formats on push.
- Pull request review is the last line of defence.

## When a credential has been committed

Treat it as compromised, even if the commit was never pushed or was removed at once:

1. Revoke or rotate the credential first.
2. Then remove it from the repository.
3. Report it as described in [SECURITY.md](SECURITY.md) when it gave access to anything beyond your own machine.

Rewriting the Git history does not make a published credential secret again: forks, clones and caches keep it.
