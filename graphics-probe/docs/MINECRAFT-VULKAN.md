# Optional Minecraft Vulkan on PS4

Minecraft 26.3 / LWJGL 3.4.3 can load RADV from the same platform PRX that supplies EGL. OpenGL remains the default: the supplied 26.3 client orders its default backends as OpenGL, then Vulkan. Only its explicit Vulkan preference reverses that order. This implementation does not modify `options.txt` or any saved renderer preference.

## Integration

- `vkGetInstanceProcAddr` is exported by `liborbis_lwjgl_platform.so`. There is no second driver PRX or Linux Vulkan loader; RADV and radeonsi share the Orbis runtime, GPU arena and VideoOut infrastructure.
- SDL loads that exact library and returns the same entry point LWJGL resolves. Required instance extensions are `VK_KHR_surface` and `VK_EXT_headless_surface`; the existing Orbis headless WSI presents through VideoOut.
- Vulkan windows reserve the single fullscreen window without creating an EGL surface or context. GL and Vulkan cannot simultaneously own scanout. Destroy Vulkan objects and their swapchain before destroying the SDL window; an OpenGL fallback can then create its own window.
- Input starts after WSI opens VideoOut, matching the console keyboard's existing ordering requirement. The normal pointer uses a bounded CPU overlay after the GPU wait and before the flip. No OpenGL calls occur in the Vulkan pointer path.
- VMA uses the official pinned generated JNI sources and caller-supplied Vulkan function pointers. All 73 Linux-side JNI exports are checked against the official JAR. The two upstream Windows-handle natives are excluded, as they are in LWJGL's Linux build.
- Vulkan availability requires both the updated runner `app.jar` and an explicitly Vulkan-capable bundle. The packager rejects an old platform PRX missing the entry point or window/input bridge. Older Minecraft profiles retain their current native libraries.

## NEO fix inventory

| Fix | RADV path / treatment |
| --- | --- |
| NEO GPU profile, 16 render backends, Sony tile tables | Shared DRM/AMD code: patches 0013, 0016, 0017, 0032. |
| Sony work distribution and raster configuration | RADV patch 0019 and shared AMD patches 0020/0021. The radeonsi-only 0029 equivalent need not be copied. |
| Tile swizzle disabled | Shared `ac_surface`: patch 0033. |
| Alternate tiling, 16-pipe layout, depth descriptor layout | Shared AMD/addrlib portions of 0047 apply to RADV as well. RADV emits the shared `ac` depth descriptor. |
| Gladius depth metadata | Preserve RADV's measured `use_hiz=false` workaround in 0022. Radeonsi's newer HTILE enablement is not enabled for RADV without console evidence. |
| CMASK / color fast clear | The pinned RADV source already has `ORBIS_CMASK` off by default. Patch 0048 additionally applies radeonsi 0041's Gladius safety rule before generic `forcecompress` overrides. NEO experiments require `ORBIS_FAST_CLEAR=1` as well as the existing CMASK opt-in; normal clears write pixels. Base-family behavior is unchanged. |
| Strict vertex fetch alignment | Already implemented in shared `ac_get_safe_fetch_size` / RADV ACO and included in the shader cache identity. Radeonsi 0042 ports that existing rule. |
| Geometry-ring padding | Already implemented by `radv_orbis_gs_ring_bytes`; radeonsi 0045 ports the measured RADV behavior. |
| Padded offchip tessellation allocation | Shared `ac_fill_tess_info` portion of 0046 reaches RADV; RADV already has the Sony factor-ring path. |
| DMA copies, fence polling and overlap | Radeonsi-specific 0036/0043/0044 do not replace RADV's existing submission/fence/WSI implementation. |

No unsupported device features are fabricated. Minecraft requires Vulkan 1.2, dynamic rendering, push descriptors, synchronization2, vertex attribute divisor and swapchain support, plus the feature bits checked by the acceptance probe. Missing capabilities fail explicitly.

## Build

Use the pinned source trees and SDK required by the existing graphics build. Build Mesa with the complete patch chain, in a separate output directory:

```sh
python3 graphics-probe/tools/build_mesa.py \
  --sources graphics-probe/build/sources --sdk /home/wonder/PS4Toolchain \
  --output graphics-probe/build/vulkan-mesa --radeonsi
make -C graphics-probe vulkan-bundle LWJGL_343_JARS=/path/to/official-lwjgl-3.4.3-jars
make -C graphics-probe vulkan-probe LWJGL_343_JARS=/path/to/official-lwjgl-3.4.3-jars
make -C orbis-java-app package
```

The bundle target consumes the existing, audited native builds for the other LWJGL profiles. Outputs: `graphics-probe/build/graphics-bundle-vulkan/`, `graphics-probe/build/vulkan-probe/`, and the runner package from `orbis-java-app/build-runner/output/`. Passing `--vma-sdl` to `package_graphics_bundle.py` explicitly enables this optional capability; ordinary packaging continues to produce a GL-only bundle.

## Console acceptance and rollback

Upload the optional bundle to a separate directory, e.g. `/data/orbis-jdk/graphics-radeonsi-vulkan`, and the probe's JAR, metadata and libraries to `/data/orbis-jdk/vulkan-probe`. Install the updated runner package. Preserve the current `runner.conf` and JDK/heap settings; apply the paths from `runner.conf.example` only when deliberately launching the probe. The probe prints loader identity, device/API, Minecraft's required extension and feature checks, surface support, VMA allocation, and each acquisition/submission/presentation result. A passing run presents 120 red/green/blue frames through VideoOut.

The probe is separate from the Minecraft instance. It never selects Vulkan for that instance. After a passing console run, restore the launcher configuration with the optional bundle path, and select Vulkan through Minecraft's normal graphics API option. Test a world, menu input/pointer, returning to OpenGL, and both Base and NEO hardware where available.

Rollback: restore the saved runner configuration and previous bundle path. If an active bundle is updated in place, retain all original files and its manifest together; restore both binaries and hashes. Host tests and target PRX audits establish integration contracts, not successful console rendering. Record real probe/game logs before calling hardware acceptance complete.

## Verification recorded on 2026-10-09

The pinned Mesa build completed with patches 0048/0049 and SDK symbol/link/wchar audits. The platform PRX exports Vulkan dispatch and all 27 NativePlatform JNI methods, with verified WSI archive provenance. VMA exports all 73 expected Linux JNI entry points without Vulkan imports. The runner package and all five bundle profiles pass their packaging checks; only the 3.4.3 SDL profile includes VMA.

Host coverage includes real native window lifecycle, Vulkan dispatch/surface forwarding, scanout readiness, bounded pointer drawing, SDL loader identity, VMA ABI, the NEO metadata gate, probe compilation and optional bundle selection. The standalone Mesa probe permits only the two named WSI hooks as unresolved **weak** imports; strong or unknown imports remain rejected.

The full graphics suite retains three pre-existing failures: `test_local_http_manifest_libraries_assets_and_atomic_tree` (stale Minecraft library fixture), `test_platform_prx` (expected dependency set omits WebBrowserDialog), and `test_sdl_gamepad` (missing SDL test-library environment). These do not constitute console rendering validation. No PS4 Vulkan probe or Minecraft Vulkan session has yet been run.

Prepared artifacts are staged separately at `/data/orbis-jdk/graphics-radeonsi-vulkan`, `/data/orbis-jdk/vulkan-probe`, and `/data/orbis-jdk/vulkan-runner-update-20261009`. The latter contains the updated runner package. Existing runner settings and Minecraft options remain untouched. Install the updated runner and run the separate acceptance probe before selecting Vulkan in Minecraft.
