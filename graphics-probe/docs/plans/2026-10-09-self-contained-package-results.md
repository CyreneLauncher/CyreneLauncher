# Self-contained PKG results — 2026-10-09

Release: Orbis Launcher 0.57, title OJDK00003.
Artifact originally built at: `orbis-java-app/build-selfcontained/output/IV0000-OJDK00003_00-ORBISJARRUNNER01.pkg`.
After workspace cleanup, preserved at: `../releases/Orbis-Craft-0.57-bundled.pkg`.
Size: 776,601,600 bytes (740.625 MiB).
SHA256: `257030e3e6db29f1b948d150b33ba58685695f86ea3966031b22a8d061b09ec7`.

The installed stage verifies 1,114 files plus its generated inventory. It includes
848 original JDK export files across Java 17/21/25, the working combined
OpenGL/Vulkan graphics payload and desktop adapters for each JDK, eight official
launcher dependencies per JDK, and a fresh launcher build. Provided dependencies
resolve inside the JDK; downloaded game dependencies remain under `/data`.

Existing native exports and the old runner output were preserved. No Mesa or JDK
rebuild, deployment, user-data deletion, source-port change, or pin update was
performed. Build-info, source/object files, logs and temporary JVM probe options
are absent from the generated installed payload. Independent review found no
blocking issues; its two robustness suggestions were implemented: atomic,
exclusive default-settings publication and trailing-slash runtime normalization.

## Verification

- All explicit runner host/source checks passed (`make -C orbis-java-app check`).
- Launcher logic regression passed eight 6,000-step runs, including launch
  transactions, rollback, installs and the new launcher-only bootstrap checks.
- Local native SDK compilation/linking and runner binary checks passed.
- Runtime payload inventory/hash verification and real staged bootstrap dependency
  resolution passed for all three JDKs.
- Five packaging unit tests passed, covering stale files, hashes, paths, links and
  permitted base-file changes during reassembly.
- Installed-runtime configuration and first-start preservation checks passed.
- The generated PKG passed PkgTool header, content digest and signature validation.

Console acceptance is pending. In particular, verify default publication on the
console `/data` filesystem, a launcher with no preinstalled game, JDK 17/21/25
game selection, OpenGL/Vulkan rendering, and runtime-root restoration on exit.
Existing settings are preserved: compare backed-up legacy settings with the new
defaults before testing. See ../../../PACKAGING.md for the layout and upgrade
procedure. Retain recovery bundles and old exports until console acceptance.
