# Security Policy

## Scope

This repository contains a privileged Android manager and patching pipeline. Bugs that affect shell command safety, update verification, signing checks, secret handling, or patching integrity should be treated as security-relevant.

## Reporting

Please avoid disclosing sensitive details publicly until maintainers have had a chance to assess the issue.

When reporting, include:

- A concise description of the impact
- Affected versions or commits
- Reproduction steps or proof of concept
- Any prerequisites needed to trigger the issue

## Sensitive data handling

Never include the following in public reports:

- Real SuperKeys
- Signing keys or keystore material
- Full private logs containing device identifiers or secrets
- Personally identifying information from bug reports

If private reporting channels are unavailable, redact secrets first and share only the minimum information needed to reproduce the problem.
