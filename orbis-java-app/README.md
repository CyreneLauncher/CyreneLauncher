# Orbis OpenJDK 17 and independent JAR runner

Build the runner PKG once, install it on the PS4, then upload a selected JDK
and ordinary application JAR over FTP. The package contains the native launcher,
Java bootstrap, screen/keyboard support and application loader libraries.

From the repository root:

```sh
make CONF=orbis-x86_64-server-fastdebug images
make -C orbis-java-app runner
make -C orbis-java-app package
make -C orbis-java-app jdk-export JDK_CONF=orbis-x86_64-server-fastdebug
```

The app Makefile consumes completed JDK outputs; `jdk-export` never builds the
root JDK. `package` builds independently of runtime conversion. Override SDK,
TOOLCHAIN, BOOT_JDK, JNI_HEADERS, JNI_PLATFORM, PKGTOOL and DOTNET when needed.
`clean` removes app generated runner and export trees. Checks live in
`orbis-java-app/tests`; production build/native helpers live in `tools`.

Install `orbis-java-app/build-runner/output/IV0000-OJDK00003_00-ORBISJARRUNNER01.pkg`.
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
```

All seven configured paths must be absolute and shorter than 512 bytes. `debug` accepts only `true` or `false` (default `false`). `display` accepts `console` (default), `desktop` or `gpu`; desktop uses `desktop_bundle` (default `/data/orbis-jdk/cacio`), whose path cannot contain `:`. `display=gpu` gives VideoOut and input to the separately installed graphics bundle at `graphics_bundle` (default `/data/orbis-jdk/graphics`): the CPU screen console, display and input services are not started, application output goes only to `output_log`, and `<graphics_bundle>/lib` is appended to `java.library.path`. Startup fails unless `<graphics_bundle>/graphics-bundle.json` is readable; that path also cannot contain `:`. UTF-8 entries use `key=value`; blank lines and
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

Exact configure commands and compiler differences: see ../README.md.

### Runtime metrics and network interfaces

Orbis filesystem space queries use the kernel filesystem ABI directly rather
than the toolchain statvfs wrapper. Process CPU time comes from checked getrusage
counters; process load is sampled and normalized by the JVM active processor
count. System CPU load uses kern.cp_time when the kernel exposes it. Unsupported
counters return unavailable rather than a fabricated idle value; the first CPU
load sample also has no previous interval. Console confirmation of these metrics
is still required.

IPv4 interface addresses and MACs come from SceNet. NetCtl provides active
interface details only after its address matches the interface. IPv6 discovery
and unsupported interface capabilities are not supplied by this adapter. Swap
reporting and third-party OSHI hardware detection are unchanged.


## Software desktop runtime exports

`DESKTOP=0` is the default and preserves `build-jdk-<configuration>/export`.
Select the audited software desktop native closure with:

```sh
make -C orbis-java-app -j2 jdk-export JDK_CONF=orbis-x86_64-server-release DESKTOP=1
make -C orbis-java-app -j2 jdk-export JDK_CONF=orbis-x86_64-server-fastdebug DESKTOP=1
```

These use `build-jdk-<configuration>-desktop/export`. DESKTOP accepts exactly
0 or 1. Each export is assembled afresh and contains schema 1
`lib/orbis-capabilities.json`: server exports declare no capabilities and ten
native modules; desktop exports declare `software-desktop` and seventeen native
modules. This records packaged facilities. Offscreen drawing, font execution,
and desktop bridge operation on PS4 remain pending.

Desktop exports bundle DejaVu Sans 2.37 at `lib/fonts/DejaVuSans.ttf` and the
full original license at `legal/orbis-desktop-fonts/LICENSE`. The pinned official
source and SHA256 hashes are in [the font provenance README](runtime-data/desktop/fonts/README.md).
The one regular face is the baseline logical-family fallback: it does not supply
distinct serif/monospaced or bold/italic faces, or universal glyph coverage.
Fonts resolve relative to java.home without host fonts or fontconfig.

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
