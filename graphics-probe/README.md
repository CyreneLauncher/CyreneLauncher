# Isolated Mesa/Orbis build

This directory builds the pinned Mesa/Zink/RADV stack as a separate PS4 probe input. It does not alter the runner or JVM. All generated files go under `graphics-probe/build/`.

From the repository root:

```sh
python3 graphics-probe/tools/prepare_sources.py \
  --lock graphics-probe/deps.lock.json \
  --output graphics-probe/build/sources
python3 graphics-probe/tools/bootstrap_deps.py \
  --output graphics-probe/build/deps/clean
OO_PS4_TOOLCHAIN=/home/wonder/PS4Toolchain \
  graphics-probe/build/sources/orbis-compat/build.sh
python3 graphics-probe/tools/build_mesa.py \
  --sources graphics-probe/build/sources \
  --sdk /home/wonder/PS4Toolchain \
  --output graphics-probe/build/target
```

The compat script defaults to an archive inside its pinned source directory, still within `graphics-probe/build/`. Bootstrap downloads exactly the versions in `deps.lock.json`, verifies every wheel and Debian package hash, extracts Flex/Bison locally, and installs Python wheels into a local venv. It requires Python 3.12 on Ubuntu 24.04 for the pinned wheel tags and Debian package versions. The SDK hashes in the lock must match the selected `--sdk` directory.

`build_mesa.py` uses the porting kit's cross file, an empty package config lookup directory, Meson's zlib wrap, and Ninja with two jobs. It injects the pinned compatibility build's five `libc16_*.o` files after the whole-archive compatibility library and before SDK libc into the Meson cross link arguments and GL link probe. It emits `build-manifest.json` with hashes of those objects and logs under the selected output. A successful status requires the corrected GL link probe, the kit's `check-wchar-link.sh` extraction audit, plus target archive, undefined-symbol and host link input audits. Run `python3 -m unittest discover -s graphics-probe/tests -v` for the build contract checks.

`results/source-audit.md` records source and ABI findings. It does not claim on-console graphics behavior.

Task 2's standalone desktop EGL executable can be built with:

```sh
bash graphics-probe/tools/build_probe.sh
python3 graphics-probe/tools/audit_link.py \
  --elf graphics-probe/build/target/graphics-probe.elf \
  --sdk /home/wonder/PS4Toolchain \
  --build-root graphics-probe/build/target
```

`build_probe.sh` links the existing Mesa static archives, the pinned compatibility archive, all five libc16 objects and the SDK libraries in the same required order. It records linker extraction evidence and runs the kit's `check-wchar-link.sh`. The executable is `build/target/graphics-probe.elf`. On device it writes bounded stage messages to UART and `/data/orbis-jdk/graphics-probe/probe.log`. It requests a desktop OpenGL 3.2 core context over the fork's EGL scanout surface. The renderer checks GLSL 150 shaders, an asymmetric textured triangle, depth, blending, and an offscreen framebuffer before presenting at least 600 frames with swap interval 1. It logs CPU frame time; GPU timing is explicitly unavailable. The local build is not a hardware acceptance result.

The pinned kit's `ps4-openorbis.cmake` documents a required libc16 ABI bridge: SDK `regcomp`, `regexec` and `vfscanf` use 16-bit wide characters, while compatibility replacements for `mbtowc` and `mbrtowc` use 32-bit wide characters. The earlier executable omitted the bridge objects; its crash inside `regexec` is consistent with that stack corruption. The corrected link includes the objects, and the extraction audit confirms the unsafe SDK archive members were not pulled in. The crash also showed a NULL argument to `regexec`, so the probe supplies `MESA_PROCESS_NAME=orbis-mesa-graphics-probe` before EGL only when the caller has not supplied a value. Both paths are covered by host checks; their effect on device still needs a rerun. Console evidence also shows `sceKernelDebugOutText` treats its text argument as raw output despite the SDK's variadic declaration, so the probe sends it an already formatted line.

Controlled failure paths can be exercised with the exact environment values `PROBE_FAIL_SHADER=1` and `PROBE_FAIL_SURFACE=1`. For a package launched from the console UI, create an empty `/data/orbis-jdk/graphics-probe/fail-shader` or `/data/orbis-jdk/graphics-probe/fail-surface` marker before launch. The first exits after the invalid shader check and releases GL resources. The second exits during EGL surface acquisition and releases the display. Neither mode performs the normal presentation run. Remove both marker files before a normal 600-frame run.

Build the separate installable probe package with `make -C graphics-probe package`. Set `SDK=/path/to/PS4Toolchain` when selecting another SDK; the same path is passed to the target link and used for FSELF, package support assets and Mesa build identity validation. It writes one PKG and a build manifest to `graphics-probe/build/output/`. This target only builds and validates local files; it performs no upload, installation or launch. Its title ID is `OGFX00001`, separate from runner `OJDK00003`. The manifest records the package SHA256, source revisions, selected SDK hashes, hashes of the libc16 bridge objects and standalone wchar extraction evidence, tool hashes and exact payload inventories. The standalone audit files remain in `graphics-probe/build/target/`.

## Device acceptance procedure

1. Record console type (base or Pro), firmware and actual execution mode if an independent verified query is available. The probe logs its SDK firmware query; it reports execution mode unavailable rather than inferring it from the renderer name.
2. Install `build/output/IV0000-OGFX00001_00-ORBISMESAPROBE01.pkg` manually. For failure checks, use FTP or a local shell to create `/data/orbis-jdk/graphics-probe/` if it does not exist, then create an empty `fail-surface` file in that directory. Launch once and require `EGL_NEGATIVE` plus failed shutdown in the log. Remove that file. Create an empty `fail-shader` file there, launch once and require `SHADER_NEGATIVE` plus failed shutdown. Remove that file. File contents do not matter. Verify by FTP listing or shell that both markers are absent before the normal run.
3. Launch the normal probe as the only active application and observe the asymmetric triangle. The run exits after at least 600 frames. Read `/data/orbis-jdk/graphics-probe/probe.log` and UART. Require an actual desktop OpenGL version at least 3.2 with a core profile, `FBO_NEGATIVE` confirming the attachment-free FBO was incomplete, all textured triangle/depth/blend pixel checks, at least 600 completed swaps, and clean shutdown. A `GL_ERROR`, `PIXEL_ERROR`, `FBO_ERROR`, `SHADER_ERROR` or `PRESENT_ERROR` is a failure.
4. Relaunch the normal application three times with both marker files absent. Each run must repeat the checks and shut down cleanly. Record base and Pro results separately; a Liverpool renderer string on Pro does not prove Pro GPU support.

The previous console run crashed during Mesa configuration parsing before rendering. This package includes the corrected libc16 link and default process name guard, but successful context creation and rendering still require a new device run. The backend decision remains pending until device logs and visible presentation confirm the checks. If a stage fails, preserve the log and investigate that stage before GLFW/LWJGL integration.

## Runner graphics bundle (LWJGL 3.2.2)

`make -C graphics-probe graphics-bundle` builds the optional bundle the runner loads in
`display=gpu` mode and writes it to `graphics-probe/build/graphics-bundle/`. Upload that
directory's contents to the runner's `graphics_bundle` path (default `/data/orbis-jdk/graphics`).
Inputs: the Mesa target build above, the pinned LWJGL 3.2.2 and dyncall 1.0 archives from
`lwjgl/lwjgl-sources.lock.json` in `build/downloads/` (or `FETCH=--fetch`), and the official
LWJGL 3.2.2 Java JARs in `LWJGL_JARS`, verified against the pinned SHA-1s.
The optional-provider build derives its 15 TinyFD JNI entry points from the verified
`lwjgl-tinyfd-3.2.2.jar` in that directory. Its SHA-1 is pinned in
`tools/make_lwjgl_metadata.py`; the local audit result JSON is not a build input.

| File | Built by | Contents |
| --- | --- | --- |
| `lib/liborbis_lwjgl_platform.so` | `tools/build_platform_prx.py` | Mesa EGL/GL/Zink/RADV, orbis-compat, libc16 bridge, EGL bridge for `orbis.graphics.NativePlatform` |
| `lib/liblwjgl.so`, `lib/liblwjgl_opengl.so`, `lib/liblwjgl_stb.so` | `tools/build_lwjgl_natives.py` | LWJGL 3.2.2 core (with dyncall 1.0), OpenGL and complete STB JNI |
| `lib/liblwjgl_tinyfd.so`, `lib/libopenal.so` | `tools/build_lwjgl_optional.py` | TinyFD cancellation and silent/no-device OpenAL ABI |
| `lib/orbis-lwjgl-3.2.2-adapter.jar` | `tools/build_lwjgl_facade.py` | GLFW facade, `Platform` shim accepting `os.name=Orbis`, `NativePlatform` |
| `graphics-bundle.json` | `tools/package_graphics_bundle.py` | schema 1 manifest, validated with the runner's `GraphicsBundle` reader |

The six native modules are SELF PRXs with `.so` names, linked with the JDK native-module
recipe (`tools/orbis_prx.py`) and per-thread PRX TLS (`orbis-java-app/tools/orbis_prx_tls.c`),
because the loader gives user PRXs no TLS block of their own. The adapter JAR must precede
`lwjgl-3.2.2.jar` on the class path and replaces `lwjgl-glfw-3.2.2.jar`; its GLFW classes are
the official bytecode except a generated `GLFW` (exact 3.2.2 descriptors; 83 of 189 methods
implemented, the rest throw `UnsupportedOperationException`) and a Java `Callbacks`. Input
comes from the runner's input service (`runner.gpu.NativeInput`). Host checks:
`test_platform_prx.py`, `test_lwjgl_natives.py`, `test_lwjgl_facade.py`,
`test_graphics_bundle.py`. The bundle preserves the exact seven-coordinate 3.2.2 profile and
schema 1 manifest. Its hashed `build-info.json` records provider behavior and callback device
gate; `legal/README.md` records source licenses and redistribution questions. None of the new
STB/optional providers has run on a console yet; see `legal/README.md` in the
bundle for licensing items still open.

### Minecraft 1.18.2 host preparation

Run `make -C graphics-probe minecraft-prepare MINECRAFT_USERNAME=OrbisOffline` to
create `graphics-probe/build/minecraft-1.18.2/`. The tool verifies the official
version metadata, client, common libraries, asset index and individual asset
objects. It uses the runner's Java metadata resolver and exact Orbis LWJGL
profile for the final classpath. `launch-manifest.json` records SHA-256 and exact
device paths for every upload file; `UPLOAD.txt` contains manual copy and
`main-class.txt` cleanup instructions. Prepared game files and the cache stay
under ignored `build/` directories. This target does not upload or install files.

For a different host Java installation, use
`python3 graphics-probe/tools/prepare_minecraft.py --version 1.18.2 --output
graphics-probe/build/minecraft-1.18.2 --cache graphics-probe/build/downloads/minecraft
--username OrbisOffline --boot-jdk /path/to/jdk17`. The default device Java home
is `/data/orbis-jdk/jdk/release`; `--java-home` selects another absolute device
path. The offline identity is for a local title-screen test and uses a
placeholder token. The initial heap and graphics arena values are documented
in the generated JVM options. Device acceptance still requires the separately
authorized upload and console run.

### LWJGL compatibility probe

`make -C graphics-probe lwjgl-compat` builds `build/lwjgl-compat/` with an independent
`orbis.compat.CompatibilityProbe` target JAR, seven hash-verified official LWJGL Java JARs,
PNG/Vorbis/DejaVu fixtures and their provenance/license files, runner configuration, a
SHA-256 upload inventory, and `UPLOAD.txt` with exact manual device instructions. It uses
the graphics bundle above; it does not alter the runner PKG or standalone graphics probe.
The JVM starts with `-Xms512m`, `-Xmx1024m`, and `-Dorbis.graphics.arenaMib=512`.

The probe logs `COMPAT stage=<name> result=<ok|fail|skip>` for native loading, STB image/font/
Vorbis/callbacks, official ALC no-device and TinyFD cancellation, GLFW policy, advertised
OpenGL debug callback invocation, teardown and summary. `-Dorbis.compat.fail=<stage>` fails
immediately after a stage and exercises reverse-order cleanup. Host contract checks use
official Linux natives plus the host-built optional providers; the console must establish
Orbis allocator, executable callback trampoline, GL debug and close/relaunch behavior.
The `UPLOAD.txt` instructions require a separate authorized device operation and preserve
the prior smoke as a regression gate.

### LWJGL smoke test

`make -C graphics-probe lwjgl-smoke` writes `build/lwjgl-smoke/`: a smoke `target.jar`
(`lwjgl-smoke/src/orbis/smoke/LwjglSmoke.java`), a version JSON listing the seven official
LWJGL 3.2.2 JARs (`tools/make_lwjgl_metadata.py`), that `libraries/` tree, a `display=gpu`
`runner.conf` and `jvm-options.txt`, and `UPLOAD.txt` with the upload and log-collection
steps. Each stage logs `SMOKE stage=<name> result=<ok|fail|skip>`: versions, init, window,
context, capabilities (GL strings), shader, pixels (first-frame `glReadPixels` check), frames,
input, teardown, summary. `-Dorbis.smoke.fail=<stage>` injects a failure after a stage to
exercise teardown. `tests/test_lwjgl_smoke_contract.py` runs the init/window/context failure
paths on the host; GL, pixels, presentation and input need the console.

### Minecraft 1.18.2 delivery status (2026-10-03)

The local `OJDK00003` runner PKG, release JDK export, graphics bundle,
compatibility upload tree and official 1.18.2 client tree are prepared. The
final local checks passed: release export has 10 SELF modules; the bundle and
compatibility manifests hash 35 and 18 files; the Minecraft manifest hashes
3,094 device files (546,192,623 bytes) and its selected 36-JAR classpath
matches the production Java resolver. These are host artifact checks.

Device acceptance is **pending**. The SubmitDone PS-menu close/relaunch retest
has not happened; the compatibility probe and official client have not been run
on a console for this milestone. Follow the ordered manual backup, upload,
smoke, compatibility and title-screen record in
`results/minecraft-1.18.2-device-runs.md`. The two probe/client configurations
select `/data/orbis-jdk/jdk/release`; compare that path with the installed JDK
before launch. A title-screen result requires rendered text and textures,
mouse and keyboard input, normal exit and three clean relaunches. Silent audio
is expected. No gameplay, audible audio or Neo-mode result is established.

### Launcher controls, instances and Microsoft sign-in

The launcher adds source-aware controller/keyboard navigation, explicit editing with the PS4
system keyboard, installed-runtime instances, and Microsoft device sign-in with a QR code.
See [launcher/UPDATE.md](launcher/UPDATE.md) for controls, client-ID setup, storage behavior,
and the matching runner 0.45 PKG/full graphics-bundle update. The JDK is shared and unchanged.
New workflows require console validation; production compilation is not hardware validation.
