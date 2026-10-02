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
