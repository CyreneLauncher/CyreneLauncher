Orbis Java JAR Runner: FTP setup

`orbis-java-app/build-runner/output/IV0000-OJDK00003_00-ORBISJARRUNNER01.pkg`.
Upload the **contents** of
`orbis-java-app/build-jdk-orbis-x86_64-server-fastdebug/export/` to
`/data/orbis-jdk/jdk/`. Preserve its directory layout: `conf/`, `legal/`,
`release`, `lib/modules`, `lib/orbis-modules.list`, native libraries in `lib/`,
and `lib/server/libjvm.prx`. Native libraries with `.so` names are converted
SELF modules, not raw ELF libraries. Required symlinks are materialized for FTP.

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

All nine configured paths must be absolute and shorter than 512 bytes. `debug` accepts only `true` or `false` (default `false`). `input_diagnostics` (`true` or `false`, default `false`) sends keyboard and mouse diagnostics to UART: slot and record dumps, key transitions, held-key timing and slow device calls, each capped per session. `display` accepts `console` (default), `desktop` or `gpu`; desktop is explicit opt-in and uses `desktop_bundle` (default `/data/orbis-jdk/cacio`). The bundle path cannot contain `:` because it participates in the fixed three-JAR class path. `display=gpu` gives VideoOut and input to the separately installed graphics bundle at `graphics_bundle` (default `/data/orbis-jdk/graphics`): the CPU screen console, display and input services are not started, application output goes only to `output_log`, and `<graphics_bundle>/lib` is appended to `java.library.path`. Startup fails unless `<graphics_bundle>/graphics-bundle.json` is readable; that path also cannot contain `:`. In GPU mode `version_metadata` (default `/data/orbis-jdk/runner/version.json`) names a Mojang-style version JSON and `libraries` (default `/data/orbis-jdk/libraries`) its libraries root: before the application loads, the runner resolves that metadata, requires the exact tested LWJGL profile, validates the graphics bundle, puts the bundle's adapter JAR and the resolved libraries (minus `lwjgl-glfw`, which the adapter replaces) on the class path after the target JAR, and uses the metadata `mainClass` unless `main-class.txt` overrides it. UTF-8 entries use `key=value`; blank lines and
`#` comments are ignored, surrounding whitespace trimmed. Unknown/duplicate
keys, malformed entries and overlong paths fail startup. No shell quoting,
variable expansion or commands are interpreted. Java `user.dir` and the port's native Java file adapters use
`working_directory`; relative Java application files therefore live there.
The launcher attempts native `chdir`, but PS4 denial (`EPERM`) or unsupported
operation (`ENOSYS`) uses this existing emulation. Other cwd errors fail startup.
External native code using raw relative filesystem calls does not inherit that
emulation.
Output is overwritten each run, capped at 256 KiB and shown on screen.

For desktop mode, first build a desktop-capable JDK export and install it
separately from the default server export:

```sh
make -C orbis-java-app jdk-export JDK_CONF=orbis-x86_64-server-fastdebug DESKTOP=1
```

FTP the contents of
`orbis-java-app/build-jdk-orbis-x86_64-server-fastdebug-desktop/export/` to
`/data/orbis-jdk/jdk-desktop/`, preserving the layout, then set
`java_home=/data/orbis-jdk/jdk-desktop` in `runner.conf`.

Next build the pinned Cacio bundle with `make cacio-source` (explicit source
retrieval) and `make cacio-export`, then FTP the contents of
`build-cacio/export/` to `/data/orbis-jdk/cacio/`. The bundle is
three external JARs plus its launch options, manifest, and upstream license; it
is not part of the runner PKG or JDK export. `display=desktop` starts the bridge
before application classes are initialized. The fixed desktop is 1280x720.
The application loader and JVM remain available after `main` returns.
PS4 acceptance is pending; full AWT compatibility and movable/resizable/modal
window behavior are not claimed.

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

To switch runtimes, export release with
`make -C orbis-java-app jdk-export JDK_CONF=orbis-x86_64-server-release`, upload
to `/data/orbis-jdk/jdk-release/`, and set `java_home` accordingly. Each runtime
supplies its own module list; switching requires no runner reinstall. Use exports
produced by this checkout: older runtime exports are rejected during native
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
entry/result/error events. Entries are written and closed immediately; timestamps
and thread/module identifiers distinguish sources. The previous run is saved as
`runner-debug.log.1`. These filenames are reserved and cannot be used as the
application JAR or output log in debug mode. The current debug log has no size cap, so disable debugging
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
cannot prove PS4 execution. Release startup stalls and compiler instability
remain unresolved hardware limits. The default runtime includes JavaBeans (`java.desktop`) and its data-transfer/preferences
module dependencies for ordinary Java library compatibility. Desktop native
libraries and GUI support are not provided. The runtime is headless; full Java
SE compatibility, desktop APIs and arbitrary external native libraries are not
established. No console deployment is performed by the build commands.
