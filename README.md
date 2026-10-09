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

Linux x86_64 with Python 3.12, OpenJDK host build dependencies, LLVM/Clang, CMake, Ninja, .NET, and the exact compatible SDK/toolchain is required. Mesa verifies the SDK and dependency hashes in `graphics-probe/deps.lock.json`. The open-source SDK distributions are pinned in `tools/toolchain.lock.json`; `tools/provision_toolchain.py` downloads and verifies them and builds LibOrbisPkg from its pinned source revision.

The build executes serial JDK builds, all LWJGL profiles, native Mesa, audio/SDL, launcher, desktop support, runner, and package checks. Logs live in `build-logs/`. The validated PKG appears in `dist/`. Installation is manual. Title ID and `/data/orbis-jdk` paths remain compatible with earlier packages.

## Individual steps and reuse

```bash
./orbis bootstrap       # Restore missing pinned repositories; never reset existing work
./orbis jdks            # Build all three runtimes, reuse matching completed exports
./orbis jdks --jdk 21   # Build only JDK 21 with BOOT_JDK21
./orbis native          # Build graphics/audio/controller modules
./orbis launcher        # Build launcher, desktop export and graphics bundle
./orbis assets          # Build native/desktop assets without JDK exports
./orbis seal            # Verify completed JDK/native exports and record inventories
./orbis package         # Assemble existing verified exports; no JDK/native rebuild
./orbis export          # Produce dist/cyrene-components.tar.gz and its checksum
./orbis import --archive /path/to/cyrene-components.tar.gz
```

You can save these path variables in `.cyrene.env` instead of exporting them each time; copy `.cyrene.env.example` and edit it. The CLI loads that local Bash environment file automatically. It is excluded from Git.

Keep source checkouts clean and pinned. Component recipes reuse unchanged successful builds. Existing JDK exports require matching lock/libc provenance and their original build inventory; preserve stale exports before rebuilding. Import requires the archive checksum and matching repository/JDK lock files, refuses existing outputs, and rejects archive links and traversal. Assembly checks every packaged file's hash against the inventory recorded after successful builds. Missing or altered recorded inventories are rejected; package assembly does not certify arbitrary files on disk.

To reuse exports from a release, check out that release's source first, download its `cyrene-components.tar.gz` and `.sha256`, run `import`, then `package`. Dependencies needed for compilation are fetched and verified automatically. Package-only assembly uses host JDK 17 and 25; it does not require a host JDK 21.

```bash
gh release download v01.06 -R CyreneLauncher/CyreneLauncher \
  --pattern 'cyrene-components.tar.gz*' --dir /tmp/cyrene-components
./orbis import --archive /tmp/cyrene-components/cyrene-components.tar.gz
./orbis package --version 01.06
```

## GitHub Actions

Both workflows run on **GitHub-hosted Ubuntu 24.04**. No self-hosted runner, local SDK paths or local build cache is required. The only required repository secret is `ORBIS_MSA_CLIENT_ID`, the launcher application's registration ID; player accounts are never supplied to CI.

`Build Cyrene Launcher` provisions LLVM 18, pinned Temurin host JDKs, .NET 8, OpenOrbis v0.5.4 and payload SDK v0.9. SDK archive SHA-256 values and the accepted OpenOrbis ABI files are checked before compilation. The PKG CLI is built from pinned OpenOrbis/LibOrbisPkg source. These upstream SDKs match the installed build inputs; no custom SDK fork is needed.

Three matrix jobs build and audit JDK 17, 21 and 25. A parallel assets job builds native radeonsi/RADV, LWJGL, OpenAL, SDL, the launcher and desktop support. The package job downloads their compressed artifacts, verifies hashes, file inventories and source/SDK provenance, then assembles and validates the PKG. Tar archives preserve executable permissions across Actions artifact transfers. All jobs save their build logs, including nested compiler diagnostics. The CLI streams compiler output live when running in Actions.

Run a complete build from the GitHub **Actions → Build Cyrene Launcher → Run workflow** page, or use:

```bash
gh workflow run release.yml -R CyreneLauncher/CyreneLauncher \
  -f version=01.06 -F draft_release=true
gh run list -R CyreneLauncher/CyreneLauncher --workflow release.yml
```

The successful package job uploads `cyrene-release-XX.YY`, containing the PKG, reusable component archive, SHA-256 checksums, runtime manifests and SDK/tool lock. Intermediate runtimes and logs are retained as Actions artifacts for 14 days. To download the final output without creating a release:

```bash
gh run download RUN_ID -R CyreneLauncher/CyreneLauncher \
  --name cyrene-release-01.06 --dir dist
```

`draft_release=true` additionally creates a draft GitHub release after every package check passes. Installation remains manual. Package validation does not establish console compatibility; record console testing before publishing the draft. Initial builds compile everything from source and can take several hours; no previous build on your computer is required.

## Source ownership

`repositories.lock.json` pins the native port repositories and consolidated JDK builder. `orbis-java-app/tools/runtime-package-inputs.json` defines the package layout and Java dependencies; the CLI generates fresh export inventories before packaging. Upstream license and contributor notices remain with the source. No SDK binaries, accounts, dependency caches or generated packages are tracked.
