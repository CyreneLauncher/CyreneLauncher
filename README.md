# Cyrene Launcher

Minecraft Java launcher and runtime integration for PS4/Orbis. Includes Microsoft sign-in, version/modpack management, controller input, desktop support and PKG assembly. Players use their own account that owns Minecraft: Java Edition; no account is pre-created.

The graphics port uses native radeonsi for OpenGL and RADV for Vulkan. Zink is excluded by the native driver audit. JDK 17, 21 and 25 are reconstructed from pinned upstream source plus the Orbis patches in [orbis-openjdk](https://github.com/CyreneLauncher/orbis-openjdk).

## Build locally

Clone this repository into a directory named `orbis-launcher` within a workspace. Pinned component repositories are cloned beside it automatically:

```bash
git clone https://github.com/CyreneLauncher/CyreneLauncher.git orbis-launcher
cd orbis-launcher
export ORBIS_SDK=/path/to/ps4-payload-sdk
export PS4_TOOLCHAIN=/path/to/PS4Toolchain
export BOOT_JDK17=/path/to/host-jdk17
export BOOT_JDK21=/path/to/host-jdk21
export BOOT_JDK25=/path/to/host-jdk25
export PKGTOOL=/path/to/PkgTool.Core.dll
# Configure ORBIS_MSA_CLIENT_ID or the untracked secrets.env for release sign-in.
./orbis doctor
./orbis build
```

Linux x86_64 with Python 3.12, OpenJDK host build dependencies, LLVM/Clang, CMake, Ninja, .NET, and the exact compatible SDK/toolchain is required. Mesa verifies the SDK and dependency hashes in `graphics-probe/deps.lock.json`. SDKs and proprietary files are not supplied by this repository.

The build executes serial JDK builds, all LWJGL profiles, native Mesa, audio/SDL, launcher, desktop support, runner, and package checks. Logs live in `build-logs/`. The validated PKG appears in `dist/`. Installation is manual. Title ID and `/data/orbis-jdk` paths remain compatible with earlier packages.

## Individual steps and reuse

```bash
./orbis bootstrap       # Restore missing pinned repositories; never reset existing work
./orbis jdks            # Build all three runtimes, reuse matching completed exports
./orbis native          # Build graphics/audio/controller modules
./orbis launcher        # Build launcher, desktop export and graphics bundle
./orbis package         # Assemble existing verified exports; no JDK/native rebuild
./orbis export          # Produce dist/cyrene-components.tar.gz and its checksum
./orbis import --archive /path/to/cyrene-components.tar.gz
```

Keep source checkouts clean and pinned. Component recipes reuse unchanged successful builds. Existing JDK exports require matching lock/libc provenance; preserve stale exports before rebuilding. Import requires the archive checksum and matching repository/JDK lock files, refuses existing outputs, and rejects archive links and traversal. Assembly checks every packaged file's hash.

To reuse exports from a release, check out that release's source first, download its `cyrene-components.tar.gz` and `.sha256`, run `import`, then `package`. Dependencies needed for compilation are fetched and verified automatically.

```bash
gh release download v01.06 -R CyreneLauncher/CyreneLauncher \
  --pattern 'cyrene-components.tar.gz*' --dir /tmp/cyrene-components
./orbis import --archive /tmp/cyrene-components/cyrene-components.tar.gz
./orbis package --version 01.06
```

## GitHub Actions

`CLI checks` runs on GitHub-hosted Ubuntu. `Build Cyrene Launcher` is manually dispatched on a dedicated self-hosted Linux x64 runner labeled `cyrene`. Set repository variables `ORBIS_SDK`, `PS4_TOOLCHAIN`, `BOOT_JDK17`, `BOOT_JDK21`, `BOOT_JDK25`, and `PKGTOOL` to absolute runner paths, and the `ORBIS_MSA_CLIENT_ID` secret to the launcher application's registration ID. This workflow is for trusted manually selected source revisions.

```bash
gh workflow run release.yml -R CyreneLauncher/CyreneLauncher \
  -f version=01.06 -F draft_release=true
```

The workflow runs the same CLI, validates the PKG, uploads outputs/logs, and optionally creates a draft release with the PKG, component archive, checksums, and runtime manifests. Runner registration and SDK provisioning must be completed before it can run. A build passing package audits does not establish console compatibility; record console testing before publishing a draft.

## Source ownership

`repositories.lock.json` pins the native port repositories and consolidated JDK builder. `orbis-java-app/tools/runtime-package-inputs.json` defines the package layout and Java dependencies; the CLI generates fresh export inventories before packaging. Upstream license and contributor notices remain with the source. No SDK binaries, accounts, dependency caches or generated packages are tracked.
