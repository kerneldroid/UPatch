# Contributing

Thanks for improving UPatch.

## Development setup

You will usually need:

- JDK 21
- Android SDK / Build Tools
- Android NDK matching the version pinned in `build.gradle.kts`
- Rust stable
- `cargo-ndk`

Recommended first steps:

1. Copy the settings you need from `fork.properties.example` into local Gradle properties.
2. Build once with `./gradlew assembleDebug`.
3. Keep changes focused and easy to review.

## Before opening a pull request

- Make sure the app still builds.
- Update docs when behavior, build requirements, or support expectations change.
- Avoid placeholder URLs, placeholder package names, or local-only branding in committed files.
- Keep shell commands quoted when they consume paths or user-controlled input.
- If you touch patching scripts, validate them with `sh -n` and explain any behavior changes in the PR.

## Code review expectations

Reviewers will typically look for:

- Clear failure handling
- Safe shell command construction
- Backward compatibility where practical
- Minimal unrelated formatting churn
- User-visible changes documented in the PR description

## Reporting bugs well

For manager-side bugs, include:

- Device name
- Android version
- Kernel version
- UPatch manager version
- KernelPatch version
- Steps to reproduce
- The bug report archive from the manager when available

For patching failures, boot failures, kernel panics, or low-level patch-core issues, provide the same evidence and also report the core issue upstream to KernelPatch when appropriate.

## Security-sensitive changes

Do not post real SuperKeys, signing secrets, or private release credentials in issues, pull requests, screenshots, or sample configs.
