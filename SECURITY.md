# Security Policy

## Supported Versions

| Version | Supported |
| ------- | --------- |
| Latest  | Yes       |

## Reporting a Vulnerability

Please do not report security vulnerabilities through public GitHub issues.

Instead, use GitHub private vulnerability reporting:

[Report a vulnerability](https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/security/advisories/new)

You can expect an initial response within 5 business days. After confirmation, a fix will be released as soon as practical. Contributors will be credited in release notes unless they prefer to remain anonymous.

## Security Practices

- Credentials, private keys, tokens, and other secrets must not be committed to the repository.
- Contributors should install the repository Git hooks with `./scripts/setup-hooks.sh`.
- GitHub secret scanning and push protection provide server-side protection.
- Changes to the default branch require the repository ruleset and code-owner review.
