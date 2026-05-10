<div align="center">
<a href="https://github.com/kerneldroid/UPatch/releases/latest"><img src="https://raw.githubusercontent.com/kerneldroid/UPatch/main/app/src/main/ic_launcher-playstore.png" style="width: 128px;" alt="UPatch logo"></a>

<h1 align="center">UPatch (EXPERIMENTAL FORK)</h1>
<p align="center"><strong>EXPERIMENTAL</strong> Android kernel and system patch manager built around KernelPatch.</p>

[![Latest Release](https://img.shields.io/github/v/release/kerneldroid/UPatch?label=Release&logo=github)](https://github.com/kerneldroid/UPatch/releases/latest)
[![GitHub License](https://img.shields.io/github/license/kerneldroid/UPatch?logo=gnu)](/LICENSE)

</div>

**WARNING: This is an EXPERIMENTAL FORK.** Use at your own risk. This project does not guarantee stability.

UPatch manages KernelPatch-based root and patch workflows on supported ARM64 Android devices. This fork focuses on safer manager-side behavior, a more reliable patch/unpatch pipeline, and cleaner project hygiene for contributors and maintainers.

## Highlights

- Safer shell command handling in manager-side root operations.
- More predictable patch, install, unpatch, and export flows with stronger error handling.
- Hardened boot image patch/unpatch scripts with fixed argument parsing and proper exit codes.
- Cleaner repository defaults, issue templates, and contributor documentation.

## Features

- KernelPatch-based root management.
- APM support for module workflows similar to Magisk modules.
- KPM support for kernel-side modules.
- Boot image patching, flashing, next-slot installation, and unpatch flows.
- Bug report export and diagnostics collection from the manager.

## Support matrix

- **Architecture:** ARM64 only.
- **Kernel versions:** 3.18 - 6.12.
- **Kernel config:** `CONFIG_KALLSYMS=y` is required.
- **Recommended:** `CONFIG_KALLSYMS_ALL=y`.
  - `CONFIG_KALLSYMS_ALL=n` has only initial support and may be less reliable.

## Security note

The **SuperKey** is more sensitive than ordinary root access. Use a strong, unique value and never publish it in screenshots, logs, bug reports, or recordings.

## Build from source

### Prerequisites

- JDK 21
- Android SDK / Build Tools
- Android NDK (see `build.gradle.kts` for the pinned version)
- Rust stable toolchain
- `cargo-ndk`

### Build steps

1. Copy any branding or publishing overrides you need from `fork.properties.example` into `gradle.properties` or `~/.gradle/gradle.properties`.
2. Build the manager:

```bash
./gradlew assembleDebug assembleRelease
```

The first Gradle run downloads dependencies, so a network connection is required.

## Repository layout

- `app/` — Android manager app and native integration.
- `apd/` — privileged/runtime components.
- `docs/` — repository documentation and technical notes.
- `.github/` — CI, issue templates, and project automation.

## Getting help

- Repository docs: [docs/](docs/)
- Bug reports: use the issue templates and attach the manager-generated bug report archive whenever possible

For kernel panics, patching regressions, or failures that clearly originate in the patch core rather than the manager UI, include the same evidence when reporting upstream to [KernelPatch](https://github.com/bmax121/KernelPatch).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, submission expectations, and review guidelines.

## Credits

- [KernelPatch](https://github.com/bmax121/KernelPatch/) — the patch core.
- [Magisk](https://github.com/topjohnwu/Magisk) — `magiskpolicy`.
- [KernelSU](https://github.com/tiann/KernelSU) — parts of the app UI and module-style workflow design.

## License

UPatch is licensed under the GNU General Public License v3. See [LICENSE](LICENSE).