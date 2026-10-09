# Repository ownership and recovery

The Orbis Java/game stack uses **10 pinned sibling repositories**, plus this
launcher integration repository. Exact revisions and compatibility links live in
`repositories.lock.json`; upstream archive hashes live in
`graphics-probe/deps.lock.json` and `graphics-probe/lwjgl/lwjgl-sources.lock.json`.

| Sibling repository | Owns |
| --- | --- |
| `mesa-orbis` | Mesa upstream history, all 45 ordered local patches, Mesa build recipe |
| `lwjgl-orbis` | Four LWJGL profiles, Java/JNI adapters, dyncall/libffi adaptations, LWJGL/FreeType/shader/VMA build recipes |
| `openal-soft-orbis` | AudioOut backend, OpenAL adaptations and build recipe |
| `sdl-orbis` | SDL threading adaptations, gamepad adapter and build recipe |
| `orbis-runtime-support` | PRX runtime/TLS/environment/initializer support, linker preparation and common PRX build recipe |
| `jdk17u` | OpenJDK 17 Orbis port |
| `jdk21u` | OpenJDK 21 Orbis port; `.git` points to its existing `.orbis-git` history |
| `jdk25u` | OpenJDK 25 Orbis port |
| `orbis-compat` | Pinned compatibility implementation used by native builds |
| `orbis-porting-kit` | Pinned cross-build support |

RADV, radeonsi and Zink are drivers inside Mesa, not separate repositories.
LWJGL's four releases are profiles inside one port repository. OpenAL and SDL
are initially overlay repositories: their recipes extract hash-pinned upstream
archives and apply their platform adaptations. They are not complete upstream
source forks. Unmodified FreeType, zlib, STB, VMA and shader dependencies remain
pinned inputs; build directories are disposable outputs, not source repositories.

The launcher owns application/launcher behavior, the Minecraft compatibility mod,
acceptance tests, deployment and package assembly. It retains wrappers for the
old native-builder commands and relative links for the old source paths. Port
recipes can also be invoked directly; set `ORBIS_LAUNCHER_ROOT` when the integration
checkout is not the default sibling `orbis-launcher`.
Use launcher wrappers for pinned integration builds. Direct port entry points are
for maintainer builds and do not check their own repository revision against the
launcher lock; run the verification command before using their outputs in a release.

## Daily use

Run from the launcher root:

```sh
python3 graphics-probe/tools/repositories.py verify
```

The tool checks independent Git roots, exact commits, tracked/untracked source
changes, and relative compatibility links. Recipe wrappers check the repositories
they consume before execution. Ignored build outputs remain permitted. Existing
build commands and source paths continue to work.

Edit port code in its owning repository, run its relevant checks, and commit it
there. Then update that component's `revision` in `repositories.lock.json` to
`git -C ../COMPONENT rev-parse HEAD`, verify the integration, and commit the lock
change in the launcher. Never silently reset dirty repositories to satisfy a pin.

Mesa has two different pins: the component pin identifies the port repository
and patch series; `deps.lock.json` identifies the upstream base used by the build
checkout. Keep the base pin unchanged when only changing the patch series.
The build recipe hashes the patches into its manifest and shader-cache identity.
Do not apply the series permanently to the cached checkout, or apply it twice.

## Bootstrap and recovery

Keep the launcher and its repositories as siblings. When a pinned repository is
missing, restore it from a directory of local repositories or Git bundles:

```sh
python3 graphics-probe/tools/repositories.py bootstrap --seed-root /path/to/seeds
```

Seeds are named exactly like the `directory` fields in the lock, or
`DIRECTORY.bundle`. The tool checks out the exact commit. It leaves existing
clean pinned repositories alone, rejects dirty or mismatched repositories, and
does not leave a partial destination when cloning/checking out a pin fails.
For a shallow repository, keep its `DIRECTORY.bundle.shallow` sidecar with the
bundle. The tool restores that boundary before importing the history. The Mesa
and compatibility donors were shallow; their available histories are preserved,
including those boundaries, rather than claiming a complete upstream history.

The new repositories are local and unpublished. Their `url` fields are null;
bootstrap requires a seed/bundle for them. Assign actual published URLs only
after creating and publishing those repositories. The compatibility projects
retain their existing upstream URLs. Bootstrap with local seeds removes temporary
local origins for unpublished ports. No `/tmp` repository is authoritative.

The initial migration's seven non-JDK recovery bundles and hash snapshot are in
`../orbis-port-backups/2026-10-09/`. The three pre-existing JDK repositories remain
their own recovery sources; these bundles do not duplicate JDK histories or SDKs.
For a fresh machine, supply the exact JDK source repositories as well as the port
bundles. Back up the launcher and all sibling repositories together.

SDK/toolchain installations remain external inputs at their existing locations.
No SDK migration, Mesa/JDK rebuild, console deployment or remote publication was
performed as part of this repository split.

## Installed distribution

The launcher assembles the self-contained PKG from pinned, verified port exports.
The native source repositories remain independent. Provided runtime libraries
live inside each installed JDK; downloads and state remain in `/data`.
See [PACKAGING.md](PACKAGING.md) for the layout, build and upgrade checks.
