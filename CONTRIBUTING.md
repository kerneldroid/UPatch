# Contributing Guidelines

Read and follow these rules strictly before submitting issues or PRs. Violations will result in your issue or PR being closed immediately without review.

## Issue Reporting
- **Use Templates:** You MUST use the provided GitHub Issue templates and fill them out completely. Empty or incomplete templates will be closed.
- **Search for Duplicates:** Search open and closed issues before posting. Duplicate issues will be flagged and closed.
- **Provide Text Logs:** Provide logs as text in Markdown code blocks (```log). Do NOT upload screenshots of text logs.
- **Provide Reproduction Steps:** Include clear, step-by-step instructions to reproduce the issue.
- **Bug Reports:** If reporting a bug, you must attach the manager-generated bug report archive whenever possible.

## Pull Requests
- **Scope:** Keep PRs focused on a single issue or feature. Do not bundle unrelated changes.
- **Compile and Test:** Ensure your code compiles (`./gradlew assembleRelease`) without new warnings. Do not submit code that fails to build.
- **Architecture:** Follow the existing architecture. Keep UI logic separate from core patch logic.
- **Git History:** Squash commits into logical units with clear messages.

## Code Style
- Follow standard Kotlin idioms for the Android app (`app/`).
- Follow standard Rust idioms for the native binaries (`apd/`).
- Run internal linters and formatters before submitting.