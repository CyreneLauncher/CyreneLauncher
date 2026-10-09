# Repository split results — 2026-10-09

## Completed

Created independent sibling mesa-orbis, lwjgl-orbis, openal-soft-orbis, sdl-orbis,
orbis-runtime-support, orbis-compat and orbis-porting-kit checkouts. Retained the
existing jdk17u/jdk21u/jdk25u ports and restored JDK21 normal discovery through a
.git pointer to its existing .orbis-git directory. No JDK source was changed.

Mesa retains the donor upstream history and imported launcher patch history.
LWJGL, audio, SDL and runtime history were extracted with git subtree. Mesa and
orbis-compat donors are shallow; those boundaries are preserved. OpenAL/SDL are
source overlays against hash-pinned archives, rather than complete upstream forks.

Ten exact component pins and ten compatibility links are recorded in
repositories.lock.json. Build adaptations moved to their component repositories;
launcher wrappers retain the existing CLI and importable helper functions. The
launcher retains source preparation, package assembly, app/launcher behavior and
integration tests. Compatibility paths preserve the original effective source inputs.

## Verification

- Baseline: 24 build-contract tests plus depth and 13-case chain construction checks passed.
- After extraction: all **108 recorded original source files** and **4 recorded
  build records/artifacts** retain their original SHA-256 hashes.
- All **45 Mesa patches** apply in order to a fresh temporary checkout of the
  pinned upstream base; shader-cache patch identity is unchanged.
- **25 build-contract tests** pass, including relative seed resolution and canonical origin.
- **10 repository tests** pass, including exact pins, dirty-source rejection, invalid
  links/directories, failed-clone cleanup, recipe integration roots and shallow recovery.
- Real offline restore of **all seven non-JDK repositories** from recovery bundles
  passes, including the Mesa and compatibility shallow boundaries.
- PRX TLS host behavior and SDK-link checks pass; environment isolation/runtime
  source checks pass. Initializer/export/environment checks pass on three existing PRXs.
- Full graphics suite: **101 tests**, with the same pre-existing one failure,
  two import errors and two skips recorded before this migration. The failures are
  the Minecraft fixture module mismatch, platform test's WebBrowser dependency list,
  and SDL game's missing host-library fixture/module-level SystemExit. The additional
  shallow recovery regression was added afterward and its ten-test suite passes.
- Independent whole-change review and focused recovery review: no blocking findings.

## Recovery and limits

Seven port recovery bundles, shallow sidecars, original input hash snapshot and
the integration lock are under ../orbis-port-backups/2026-10-09 relative to the
launcher root. Existing JDK repositories and SDK installations are not duplicated
in these bundles. New port repositories remain local and unpublished.

No Mesa/JDK rebuild, console deployment, remote publication or source-ABI change
was performed. Direct port recipes are maintainer entry points; launcher wrappers
perform pinned component checks. See REPOSITORIES.md at the launcher root for
ownership, daily verification, pin updates and bootstrap instructions.
