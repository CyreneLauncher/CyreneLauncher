# Vulkan chain-to-fence implementation results — 2026-10-09

Status: built, packaged, deployed and accepted on Vulkan and GL. No push.

## Changes

- Added `patches/mesa/0051-orbis-drm-chain-to-fence.patch`; retained diagnostic patch 0050.
- Small submissions use the existing flattener. Oversized submissions link the final reserved NOP(2) trailers to subsequent chunks and a private, aligned EOP fence IB. `ORBIS_FLATTEN=1` preserves the old flatten-only behavior.
- The walker rejects invalid sizes, addresses, malformed packets, repeated/overlapping IBs and excessive depth before any tail is changed. Exact `0xffff1000` padding headers are header-only, as defined by Mesa.
- Oversized submissions wait for previous GPU work before patching and wait for their own fence before restoring all four trailer dwords. A timeout retains the pending patches and refuses further submissions.
- Both submit arms use the construction. The boot self-test builds an oversized stream. Chain validation walks all reachable IBs read-only with a depth bound. Hang snapshots retain the address/size list and a copy of the private fence IB.
- Tail writes precede the store fence and garlic flush. Optional hardware-init packets use a separate private head IB.

## Verification

- Approved Mesa cross-build: success, 195 ninja steps. Build manifest contains the matching 0051 patch hash. The pinned Mesa checkout is clean after build.
- Platform PRX relink: success.
- `python3 tests/test_orbis_chain_fence.py`: 13 ASan/UBSan subcases passed (oversized stream and reachable fence, restore/reuse, padding before chains, long padding sentinel, ordinary truncated NOP, cycle, bounds, missing trailer, short packet, zero header, overlapping chunks, trailer inside payload, depth, boot self-test; oversized and boot checks share their respective cases).
- `python3 tests/test_vulkan_bridge.py`: 2 tests passed.
- Bundle package: 73 files; all five runner profiles accepted.
- Full unittest discovery: 90 tests, 1 failure, 2 import errors, 2 skips. Same failures on both full runs; affected source/tests were untouched:
  - `test_minecraft_prepare.PrepareTests.test_local_http_manifest_libraries_assets_and_atomic_tree`: fixture expects only the older LWJGL module set.
  - `test_platform_prx`: expected dependency list omits existing `libSceWebBrowserDialog.so`.
  - `test_sdl_gamepad`: missing `ORBIS_SDL3_LIBRARY` causes a module-level `SystemExit(0)` that unittest discovery treats as an import error.
- Fresh reviewer found one Important padding-parser issue. Both new regression cases failed before correction and passed after it. No other Critical/Important finding was confirmed.

## Deployment

Used the original guarded session script: no JVM response to the thread-dump guard and no pending launch journal; changed files uploaded and read back with SHA256 verification. Only `/data/orbis-jdk/graphics-radeonsi-vulkan` was targeted:

1. `build-info.json`
2. `lib/liborbis_lwjgl_platform.so`
3. `graphics-bundle.json` (last)

## Decisions and remaining limits

- Worked in the requested main checkout because the plan explicitly names that checkout and shared build tree. Unrelated edits were preserved. Cost: no isolation from concurrent edits.
- Serialized oversized submissions and restored tails before returning, because RADV's own unchain bookkeeping does not reliably restore provider patches. Cost: reduced CPU/GPU overlap for those submissions.
- Console CP execution, cache visibility and title/world/GL behavior require the specified console checks. Cost: host correctness does not establish hardware acceptance.
- Preserved existing Sony submission rejection semantics: a nonzero return means the stream was not accepted. Cost if that assumption is wrong: partial acceptance would make restoration on that error unsafe.
- Continued the scoped build despite the unrelated full-suite failures, recording all three by name. Cost: the overall repository suite remains red.
- Deferred minor coverage gaps: direct optional Sony init-head regression and assertions for every EOP selector bit (boot test does exercise the actual emitter).

## Console acceptance

- Minecraft 26.3 on Vulkan: user confirmed title/world work and the deployed depth fix eliminates the dot grid.
- Minecraft 1.21.11 on GL: user confirmed it runs perfectly. Retrieved logs identify radeonsi/OpenGL 4.5 and report HTILE allocations for 1920x1080 and 1024x1024 depth targets.
- Retrieved Vulkan logs identify RADV/Vulkan 1.3 and successful oversized chain-to-fence submissions (including 1,837,224 dwords across 15 IBs).
- Logs are saved under `build/device-runs/vulkan-depth-tiling/acceptance/` (build outputs remain untracked).
- Retain 0050 as a bounded refusal trace. No push to the public fork. Future Mesa rebuilds still require a new yes.



## Addendum: RADV dot-grid depth sampling

User confirmed Vulkan runs in a world. The plan's guarded no-build test enabled
`-Dorbis.graphics.env.ORBIS_ALT_TILING=0`; the user confirmed the grid disappeared
and the game was closed. The option was then removed with guarded upload and
readback verification, restoring normal GL HiZ settings. Both local and remote
JVM option backups exist (20261009T134816Z set, 20261009T135219Z unset).

Prepared `0052-radv-orbis-standard-depth-tiling.patch`: a per-call depth state flag
restricts patch 0047's alternate DB layout to radeonsi. RADV uses the standard
layout read by its texture unit. No addrlib, tile-table, colour, HTILE-allocation
or depth-copy policy changes.

The host regression compiles the actual gfx7 descriptor builder with actual Mesa
structs/register definitions under ASan/UBSan. It failed on RADV's incorrect pipe
18 before the fix and passed after. It covers standard RADV pipe/banks, alternate
radeonsi pipe/banks and preserved HTILE register bits, GL-to-Vulkan switching,
alternate-tiling disabled, and another GPU family. Existing color-metadata,
chain-fence and Vulkan bridge tests also pass.

Fresh review found no confirmed Critical/Important issue. Deferred minor coverage:
direct host execution of driver initializers, stencil-only/nonzero-mip/MSAA cases,
and the host test dependency on an existing checkout/generated build headers.
Both real driver callsites compiled in the approved build and were exercised
in console runs.
The user approved the rebuild and guarded deployment. Mesa, the PRX relink and
bundle packaging completed successfully; all targeted tests passed again. The
Mesa manifest contains matching 0051/0052 hashes and its checkout is clean.

Both guarded deployment attempts failed before FTP connection with
`No route to host` at `172.20.10.4:2121`; no console files changed during either
attempt. After the user restored FTP, the guarded deployment succeeded: build-info.json,
lib/liborbis_lwjgl_platform.so and graphics-bundle.json were uploaded and verified
by SHA256 readback, with the manifest last. User confirmed the deployed Vulkan fix: no more dot-grid artefact. The user also confirmed the GL check; its logs show retained HTILE/HiZ allocation.
Final verification ran 91 tests in 58.900s: the same documented Minecraft
preparation failure, PRX-import error and SDL-discovery error, with no new
failures. These unrelated baseline issues remain outside the scoped commit.

Full prebuild discovery ran 91 tests: the same 1 failure, 2 import errors and
2 skips listed above; no new failures.
