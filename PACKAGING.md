# Installed package and repository ownership

The launcher repository assembles the release. Native sources remain in their
separate repositories, pinned by `repositories.lock.json`; see REPOSITORIES.md.
Assembly uses verified exports, rather than rebuilding Mesa or the JDKs.

```text
/app0/                         installed PKG, read-only
  eboot.bin                    native runner
  app.jar                      Java bootstrap
  defaults/                    first-start settings
  manifests/                   launcher profile and payload hash inventory
  runtimes/jdk17/               JDK 17
  runtimes/jdk21/               JDK 21
  runtimes/jdk25/               JDK 25
    lib/orbis/graphics/         Mesa, LWJGL, SDL, OpenAL and Java adapters
    lib/orbis/desktop/          Cacio desktop adapters
    lib/orbis/libraries/        launcher Java dependencies
/data/orbis-jdk/                downloads, instances, settings and caches
```

Each JDK has its own copy of the provided libraries. Runtime selection switches
Java, graphics and desktop roots together; the launch transaction restores them
after the game exits. The launcher bootstrap resolves its eight Java dependencies
from the selected installed JDK. Minecraft, loader and mod dependencies resolve
from the writable library cache under `/data`.

On a fresh install, the runner publishes complete default settings exclusively
into `/data/orbis-jdk/runner`. It preserves existing settings. The installed
launcher profile contains no Minecraft game; the user installs or selects one.
Runtime exports, graphics, desktop adapters and provided Java dependencies are
verified against `orbis-java-app/tools/runtime-package-inputs.json` before staging.
The generated payload manifest records component revisions and every file hash.
Sources, objects, build logs and graphics build-info.json are excluded.

## Building

Prepare the pinned repositories and the exports/cached dependencies identified
by the input inventory, then run:

```sh
make -C orbis-java-app package
```

The dedicated `orbis-java-app/build-selfcontained` output preserves old runner
builds and native exports. Assembly rejects unexpected or changed payload files
and replaces the generated stage only after verification. It does not download
games, rebuild native ports, deploy to the console, or delete user data.
Changing a runtime export requires an intentional inventory update and testing.

## Console acceptance and upgrade

Host verification does not establish console compatibility. Test a fresh data
setup by backing up or moving existing runner settings and launcher state first;
keep game saves, accounts, instances and downloads. Confirm first-start settings
publication works on the console data filesystem, the launcher opens without a
preinstalled game, and an existing settings file remains unchanged on restart.
Test games requiring JDK 17, 21 and 25, the working OpenGL and Vulkan profiles,
and return to the launcher after each game exits.

Existing installations preserve `runner.conf`, `jvm-options.txt`, `main-class.txt`
and `app-args.txt`. Before testing the new installed bootstrap, back these files
up and compare settings against `/app0/defaults`; old external JAR/metadata
paths and temporary probe JVM options must be removed or updated deliberately.
Known legacy JDK home paths map to the installed JDK, including trailing slashes.
Do not delete the old exports or recovery bundles until console acceptance.
