# Security Policy

## Supported Versions

| Version | Supported |
| ------- | --------- |
| Latest  | Yes       |

Only the latest release of each component receives security fixes.

## Reporting a Vulnerability

Please do not report security vulnerabilities through public GitHub issues.

Instead, use GitHub private vulnerability reporting:
[Report a vulnerability](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/security/advisories/new)

You can expect an initial response within 5 business days.
After confirmation, a fix is released as soon as practical.
Reporters are credited in the release notes unless they prefer to remain anonymous.

## Security Practices

### Credentials

- No credentials of any kind are committed to this repository, test credentials included; see [ZERO_CREDENTIALS_POLICY.md](ZERO_CREDENTIALS_POLICY.md).
- GitHub secret scanning, including generic patterns and validity checks, and push protection guard the repository server-side.
- Contributors install the repository's Git hooks with `./scripts/setup-hooks.sh`; they reject keystores and likely hardcoded secrets before a commit is made.

### Changes to the default branch

- Every change reaches `main` through a pull request that needs an approving review from a code owner.
- Commits must be signed, and the history of `main` is linear.
- Release tags cannot be deleted or moved.

### GitHub Actions

- Every external action is pinned to a full commit SHA, which GitHub enforces.
- Only GitHub-owned actions, actions from verified creators, and explicitly allowed actions can run.
- The `GITHUB_TOKEN` is read-only by default; a job is granted more only when it needs it.
- The credentials for the Gradle Plugin Portal and Maven Central, and the signing keys, are only available to release jobs running on `main`, through the `release` environment.
- Workflows from pull requests by outside contributors run only after a maintainer approves them.

### Dependencies

- Dependabot alerts report known vulnerabilities in dependencies.
- A weekly OWASP dependency-check scan runs in `nvd-cache-refresh.yml`.
- Dependencies are updated through `maintenance` pull requests.
