# Cacio runtime bundle

The runtime is built from PojavLauncherTeam/caciocavallo17 commit
`cdc19a131a0bc83057adbdcac67bc6fdebc32679`, licensed GPL-2.0-only with the
Classpath exception. `tools/cacio-source.json` records the archive URL and
SHA-256. Run `make cacio-source` explicitly to retrieve and verify it; ordinary
runner packaging does not download it. `make cacio-export` compiles the shared
and TTA runtime Java sources without Maven dependencies. Review the generated
`build-manifest.json` and preserve the upstream `LICENSE` with redistributed
JARs.

The build removes CTCScreen's Android `LD_LIBRARY_PATH` and
`libpojavexec_awt.so` initializer, replaces Android clipboard access with a
JVM-local clipboard, and excludes unused preload/font reflection helpers.
`patches/README.md` records the patch inventory. JUnit sources are not shipped.

## Runner installation and launch

Desktop mode also requires the matching desktop JDK export; the default server
export lacks the software desktop capability, fonts, and native modules. Build
it with:

```sh
make -C orbis-java-app jdk-export JDK_CONF=orbis-x86_64-server-fastdebug DESKTOP=1
```

FTP the contents of
`orbis-java-app/build-jdk-orbis-x86_64-server-fastdebug-desktop/export/` to
`/data/orbis-jdk/jdk-desktop/` and set `java_home=/data/orbis-jdk/jdk-desktop`
in `runner.conf`. See `tools/runner-files-README.txt` for complete runner,
runtime, and bundle installation steps.

The bundle contains exactly three runtime JARs. The runner does not package
them: build with `make cacio-export`, then FTP the complete
`build-cacio/export/` contents to the configured `desktop_bundle` directory
(default `/data/orbis-jdk/cacio`). Enable the mode with `display=desktop` in
`/data/orbis-jdk/runner/runner.conf`. The launcher puts the three bundle JARs
on the system class path and supplies the JVM options from
`launch-options.txt` before any AWT initialization. Those options select
Cacio's `CTCToolkit` and `CTCGraphicsEnvironment`, disable headless mode, fix
the screen to 1280x720, select Metal, and open/export JDK internals required by
this pinned source.

`runner.Main` reflectively loads `runner.desktop.DesktopRuntime` from the
system loader only in desktop mode. It initializes the runtime, creates the
application loader, then starts the native bridge before initializing the
application main class. The application loader delegates Cacio's reserved
package to the system provider and uses the platform loader otherwise. An
application must not package classes in that reserved package. The runner
keeps the context and application loaders available after `main` returns;
application/AWT threads continue to own JVM lifetime. Closing application
windows does not terminate unrelated application threads.

Before publishing a desktop session, bootstrap exercises the owning JDK
providers using 32x32 software drawing, physical and logical glyph rendering,
JPEG encoding/decoding, conversion between distinct ICC profiles, and a native
ImagingLib filter. It parses the installed DejaVu Sans TTF with a 4 MiB input
limit and caps JPEG output at 64 KiB. Failures identify the capability and
installed artifacts before application initialization. Libraries remain owned
by their normal JDK loaders. These small startup operations establish provider
availability for those paths; font coverage, image fidelity, performance and
other operations still require hardware acceptance.

`DesktopSession.copyFrame(int[])` copies one coherent full-screen image into
the caller's exactly 921600-element storage and returns its increasing
publication sequence. The caller owns and may reuse that array only after the
call returns; no live screen array is exposed. Runtime shutdown ends frame and
input submission and returns native focus to console, but does not stop AWT or
application threads.

## Support limits

Clipboard copy/paste is local to the JVM and is not connected to the PS4
clipboard. External file/URL opening and About/Quit handlers are unsupported.
Source inspection found that managed-window movement and resizing route
through the full-screen container, focus requests use the focus manager, and
modality uses peer blocking calls. Their correctness is unconfirmed. Do not
assume full AWT compatibility, movable/resizable windows, modality, correct
input routing, or PS4 frame behavior: these require hardware acceptance and
specific adaptation. Build and archive audits do not constitute GUI execution
or hardware confirmation.

RESET sends synthetic releases and reconciled presses to restore held input
state, while suppressing typed text and clicks. Pressed bindings can run again,
and real release events can also invoke application actions, including a Swing
button action on release. Reconciliation is not side-effect-free. Held keys
and buttons during overflow, disconnect and F12 need application-specific
hardware acceptance.

Local events have priority over native polling. Sustained Robot/local input
can starve native input and resets; the 2 ms polling waits bound idle waits,
not fairness. A later bounded local quota or alternation is required before
supporting that workload.
