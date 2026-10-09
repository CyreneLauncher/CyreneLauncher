# Orbis JAR runner

The PS4 application: the native runner (eboot), its Java bootstrap (`app.jar`), screen,
keyboard, mouse and controller services, and PKG packaging. Build it once, install the PKG, then
upload a Java runtime and an ordinary application JAR over FTP.

```sh
make -C orbis-java-app package
make -C orbis-java-app check
```

Override SDK, TOOLCHAIN, BOOT_JDK, JNI_HEADERS, JNI_PLATFORM, PKGTOOL and DOTNET when needed.
`clean` removes the runner build. Install
`orbis-java-app/build-runner/output/IV0000-OJDK00003_00-ORBISJARRUNNER01.pkg`.

Java runtimes are separate projects. Each sibling JDK port (`../jdk17u`, `../jdk21u`, `../jdk25u`)
exports its runtime with its own `orbis-export` directory; upload an export's contents to its own
folder, for example `/data/orbis-jdk/jdk/release-desktop/` (JDK 17, the default runner) and
`/data/orbis-jdk/jdk21/release-desktop/` (JDK 21, the optional runner for Minecraft 1.20.5+).
`runner.conf`'s `java_home` names the runtime the application starts on; the Minecraft launcher
switches it for a single launch when a version needs a newer Java (see `graphics-probe/launcher`).
The eboot uses only the JNI invocation API and `app.jar` targets Java 17, so one package serves both.

## Application configuration

Create `/data/orbis-jdk/runner/` and upload `target.jar`. An absent
`runner.conf` uses the following complete defaults:

```ini
# /data/orbis-jdk/runner/runner.conf
java_home=/data/orbis-jdk/jdk
jar=/data/orbis-jdk/runner/target.jar
working_directory=/data/orbis-jdk/runner
output_log=/data/orbis-jdk/runner/runner-output.log
temporary_directory=/data/orbis-jdk/runner/tmp
debug=false
display=console
desktop_bundle=/data/orbis-jdk/cacio
graphics_bundle=/data/orbis-jdk/graphics
version_metadata=/data/orbis-jdk/runner/version.json
libraries=/data/orbis-jdk/libraries
```

All nine configured paths must be absolute and shorter than 512 bytes. `debug` accepts only `true` or `false` (default `false`). `display` accepts `console` (default), `desktop` or `gpu`; desktop uses `desktop_bundle` (default `/data/orbis-jdk/cacio`), whose path cannot contain `:`. `display=gpu` gives VideoOut and input to the separately installed graphics bundle at `graphics_bundle` (default `/data/orbis-jdk/graphics`): the CPU screen console, display and input services are not started, application output goes only to `output_log`, and `<graphics_bundle>/lib` is appended to `java.library.path`. Startup fails unless `<graphics_bundle>/graphics-bundle.json` is readable; that path also cannot contain `:`. In GPU mode `version_metadata` (default `/data/orbis-jdk/runner/version.json`) names a Mojang-style version JSON and `libraries` (default `/data/orbis-jdk/libraries`) its libraries root: before the application loads, the runner resolves that metadata, requires the exact tested LWJGL profile, validates the graphics bundle, puts the bundle's adapter JAR and the resolved libraries (minus `lwjgl-glfw`, which the adapter replaces) on the class path after the target JAR, and uses the metadata `mainClass` unless `main-class.txt` overrides it. UTF-8 entries use `key=value`; blank lines and
`#` comments are ignored, surrounding whitespace trimmed. Unknown/duplicate
keys, malformed entries and overlong paths fail startup. No shell quoting,
variable expansion or commands are interpreted. Java `user.dir` and the port's native Java file adapters use
`working_directory`; relative Java application files therefore live there.
The launcher attempts native `chdir`, but PS4 denial (`EPERM`) or unsupported
operation (`ENOSYS`) uses this existing emulation. Other cwd errors fail startup.
External native code using raw relative filesystem calls does not inherit that
emulation.
Output is overwritten each run, capped at 256 KiB and shown on screen.

Optional files remain in `/data/orbis-jdk/runner/`, even when the working
directory or application JAR changes. Each JVM option or application argument
is one literal line. Blank lines and lines starting with `#` are ignored;
spaces inside a line are preserved. Do not add shell quote characters.

```text
# jvm-options.txt
-Xmx256m
-Dexample.message=hello world
```

```text
# app-args.txt
--input
/data/my application/input.txt
hello world
```

The second example passes three arguments. Optional `main-class.txt` contains
one fully qualified class name, such as `example.Main`; otherwise the JAR
manifest supplies `Main-Class`. Manifest class paths are supported. Module-path
launch is not supported. Bootstrap/runtime/path properties are reserved;
options replacing them fail with a startup error. Workload tuning is explicit:
no Paper detection, injected arguments, JLine/Netty defaults or Bukkit handling.

To switch runtimes, upload another JDK port's export (built in that port with
`make -C orbis-export jdk-export ...`) to its own folder and set `java_home`
accordingly. Each runtime supplies its own module list; switching requires no runner
reinstall. Use current exports: older runtime exports are rejected during native
configuration because they cannot accept configured working/temporary paths. Early JVM boot
and native library discovery also use the selected runtime home, seeded by the
native configuration handoff before constructors and JVM creation.

## Startup errors and optional diagnostics

Startup checks the selected image, JVM PRX, target JAR and writable directories.
Errors name the failing configured path on screen and in
`/data/orbis-jdk/logs/orbis-runner.log`. Check FTP placement and permissions;
`lib/modules` and `lib/orbis-modules.list` must belong to the same export.
No alternate runtime is selected after an error. Application stdout/stderr go
to the configured `output_log`; native startup diagnostics also reach UART.

Enable full runner boundary logging with `debug=true` in `runner.conf`.
The separate `/data/orbis-jdk/runner/runner-debug.log` records launcher filesystem
and environment operations, native module loading/configuration/initializers,
JVM options/creation, JNI calls, and Java application loading/invocation with
entry/result/error events. HotSpot also records Signal Dispatcher receipt of
SIGQUIT, entry/return of the thread dump VM operation, safepoint synchronization,
compiler task IDs and completion, and VM operation submission, notification,
and VM thread initialization/waits. These markers use the separate debug log
without waiting for the normal thread dump to complete. Entries are written and closed immediately; timestamps
and thread/module identifiers distinguish sources. The previous run is saved as
`runner-debug.log.1`. These filenames are reserved and cannot be used as the
application JAR or output log in debug mode. Debug writers stop appending near 8 MiB (concurrent writes can slightly exceed this limit). Disable debugging
for ordinary use. It can contain paths, JVM options and exception details.
Configuration must parse successfully before debug mode can be enabled; earlier
startup actions are summarized. Debugging requires newly exported native modules
with `orbis_set_debug_log`; replace the matching exported native libraries as well
as installing the updated runner PKG. The log traces runner-controlled boundaries,
not every JVM instruction or third-party application action. The last ENTER event
without a corresponding RETURN identifies a suspected stalled boundary.

Optional diagnostic marker files in `/data/orbis-jdk/runner/`:

- `startup-dump-after-seconds.txt`: integer 10–3600 requests one SIGQUIT when
  JVM creation remains pending and its handler is installed. VM output goes to
  `/data/orbis-jdk/logs/orbis-startup-vm.log`; a file alone does not prove a dump.
- `memory-probe.txt`: exactly `run` selects native mapping diagnostics and
  skips the application; results go to `/data/orbis-jdk/logs/orbis-runner.log`.
- `metaspace-probe.txt`: exactly `run` enables a consumed one-shot sampler
  after main returns, logging to `/data/orbis-jdk/logs/orbis-runner.log`.
- `vmmap-probe.txt`: exactly `run` enables a consumed, bounded mapping trace
  in `/data/orbis-jdk/logs/orbis-vmmap.log`.

Remove diagnostic markers for ordinary launches. Host checks and package audits
cannot prove PS4 execution. The pthread attribute ABI fix has been confirmed
on the console with the release runtime. The default runtime includes JavaBeans
(`java.desktop`) and its data-transfer/preferences
module dependencies for ordinary Java library compatibility. Desktop native
libraries and GUI support are not provided. The runtime is headless; full Java
SE compatibility, desktop APIs and arbitrary external native libraries are not
established. No console deployment is performed by the build commands.

Runtime build and export details live in each JDK port's `orbis-export/README.md`.

## Cacio initialization bundle

Desktop launch is an explicit opt-in with `display=desktop` in `runner.conf`.
Build the JVM-only Cacio bundle separately with `make -C orbis-java-app
cacio-source` (explicit pinned source retrieval), then
`make -C orbis-java-app cacio-export`. FTP the three exported JARs and their
launch options, manifest, and license to `/data/orbis-jdk/cacio/`. They remain
external to the runner PKG and JDK export. Startup reflectively initializes the
desktop runtime before the application class is loaded. The application loader
and JVM stay available after application `main` returns. See [the Cacio bundle
README](cacio/README.md) for support limits.

## Native display and input services

The runner uses one native VideoOut presenter and one native keyboard/mouse
collector for its screen console. The service interfaces and ownership,
buffering, focus, reset, and shutdown contracts are documented in
[the native display/input handoff](docs/orbis-native-display-input.md).
The runner can opt into the integrated Cacio desktop path with
`display=desktop`; console remains the default. Building or packaging does not
establish PS4 device, presentation, or GUI behavior. Full AWT compatibility,
movable/resizable windows, and modality are not claimed; managed-window behavior
and input details need hardware acceptance and further adaptation.

## LWJGL launch metadata (GPU mode, in progress)

`runner.lwjgl` resolves a local Mojang-style version JSON before any game native
code loads. `VersionMetadataResolver.resolve(versionJson, librariesRoot,
MetadataPlatform.ORBIS)` reads the `libraries` list in the format used by
Minecraft 1.18.2: rules may test only `os.name` and `os.arch`; Orbis is
evaluated as Mojang's `linux` Java track. Native classifiers (`downloads.classifiers`
and `:natives-*` coordinates) are recorded as skipped and never placed on the
class path. Artifact paths must be plain relative paths that stay below the
libraries root, symlinks included. Declared `size`, `sha1` and `sha256` are verified.
The plan lists the class path in metadata order without duplicates, the
selected `org.lwjgl` module versions and their SHA-1s. Game argument rules,
downloads, assets and `inheritsFrom` mod loader inheritance are not supported.

`LwjglProfileRegistry` accepts only exact tested sets. The single registered
profile, `minecraft-1.18.2-lwjgl-3.2.2-orbis-v1`, requires the seven LWJGL 3.2.2
modules with their audited SHA-1s. Any missing, extra, mixed or rebuilt module is
rejected. Profile selection also validates `<graphics_bundle>/graphics-bundle.json`
(schema version 1): `files` maps bundle-relative paths to SHA-256; `profiles`
entries carry `id`, the exact `modules`, `java_adapter_paths`,
`native_library_paths`, `glfw_operations` and `gl_features`. Unknown fields,
symlinks and hash mismatches fail.

Failures are `LaunchPlanException`s with a stable category: `METADATA_PARSE`,
`METADATA_UNSUPPORTED`, `METADATA_CONFLICT`, `ARTIFACT_PATH`, `ARTIFACT_MISSING`,
`ARTIFACT_MISMATCH`, `LWJGL_CONFLICT`, `PROFILE_UNSUPPORTED` and `BUNDLE_INVALID`.
These classes are part of `app.jar` but are not yet invoked by `runner.Main`;
a successful resolution does not mean LWJGL runs on Orbis. Host fixtures:
`python3 tests/test_lwjgl_metadata.py`.
