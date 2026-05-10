# Changelog

## Unreleased

### Fixed

- Corrected manager install/uninstall flows so UI state only changes after root operations actually succeed.
- Replaced unsafe shell writes in settings and install paths with quoted, deterministic commands.
- Closed transient root shells in WebUI and version/diagnostic paths to reduce leaks and stale shell state.
- Hardened boot image patch/unpatch scripts:
  - fixed broken argument handling
  - fixed incorrect exit-code propagation
  - fixed fragile shell tests
  - fixed the unpatch script passing the boot image twice to `kptools`
- Prevented several patching flow crashes by validating script output and guarding against missing parsed values.
- Ensured patch/unpatch/export state is reset consistently after failures.
- Improved module install staging and cleanup behavior for invalid or empty archives.
- Made bug report collection more robust when optional system directories are missing.

### Changed

- Aligned default branding and repository metadata with UPatch.
- Refreshed repository docs, contribution guidance, and issue/contact defaults.
