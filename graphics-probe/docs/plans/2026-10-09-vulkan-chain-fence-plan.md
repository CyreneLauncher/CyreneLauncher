# Plan: chain + fence submission for RADV on Orbis (Minecraft 26.3 Vulkan)

Handoff from Claude (2026-10-09). The user approved this change. Follow it in order, and verify on the PC
before anything goes to the console.

## Where things stand

- Minecraft 26.3 runs on Vulkan (RADV GLADIUS). It renders the loading screen, then crashes with
  `VK_ERROR_DEVICE_LOST: Failed to present image`.
- **Root cause, from the console logs.** The instance's `game/mesa.log` shows
  `orbis-drm: submission does not fit the 524288 dword staging buffer`.
  - `ac_orbis_drm.c` flattens RADV's chained IBs into one 2 MiB staging slot per submission
    (`orbis_build_flat` → `orbis_copy_ib`, slot = `ORBIS_FENCE_CB_BYTES`).
  - The title-screen command buffer is one RADV chain of IBs: 360, 16432, 36920, 77936, 159912, 258200 dw,
    and more after that. So more than 550k dwords; it is **not** a loop.
  - The refused submission's fence never signals. Then the 5 s fence-slot cap fires and the device is lost.
- **Ruled out:**
  - Chain loop: diagnostic patch 0050 found none.
  - `VK_AMD_buffer_marker` checkpoints: hiding the extension produced an identical size. That experiment was
    reverted in source, but the deployed platform PRX still has it until the next upload.
- Each later IB starts with `SET_SH_REG SPI_SHADER_PGM_LO_VS` (`c0047600 00000048 …`). The VS may be rebound
  very often. That matters for performance, not correctness (see step 4).
- **Committed on main:**
  - `0074ed3`: layer queries answered like the loader.
  - `9e34e02`: LWJGL 3.4.3 `getLibraryPath`.
- **Uncommitted:** `graphics-probe/patches/mesa/0050-orbis-drm-chain-loop-diagnostic.patch` (the chain trace).
  Keep it, or fold it into the new patch.
- **Console state:**
  - `runner.conf` uses `graphics_bundle=/data/orbis-jdk/graphics-radeonsi-vulkan`. That bundle has the same
    radeonsi GL as before, plus RADV patches 0048–0050. Its backup is
    `runner.conf.before-vulkan-probe-20261009T120530Z`.
  - PKG 0.56 is installed.
  - The user's 26.3 instance (`33d1ef0dc7d2441a851112075f894c35`) has "Prefer Vulkan" set.

## Design: stop copying, chain to our fence

The GPU (gfx7 CP) follows `IT_INDIRECT_BUFFER` with CHAIN (bit 20 of the size dword) natively. The port flattened
only because its own end-of-pipe fence, appended after a chain, was unreachable; see the comments around the
CHAIN bit in `ac_orbis_drm.c`. The kernel's amdgpu path appends the fence to the **last** IB of the chain.
Do the same:

1. **Walk each RADV chunk's chain** without copying, from `dcb[i]`/`dcb_bytes[i]`. Reuse `orbis_copy_ib`'s
   parsing rules: the NOP padding quirk (`0xffff1000` padding headers are skipped, not trusted) and the
   arena-bounds check on chain targets. Then find the **final** IB, the one whose tail has no CHAIN packet.
2. **Link the chunks into one stream.**
   - RADV submits 3 IB chunks: preamble, body, postamble.
   - Patch the tail of each chunk's final IB to CHAIN to the next chunk's first IB. RADV reserves the last
     4 dwords of each CS for this (`radv_amdgpu_cs_chain` writes `cs->buf[cdw-4..cdw-1]`; unchained, it holds
     `PKT3(NOP,2)` + 3 dwords).
   - Patch the last chunk's final IB to CHAIN to a small **fence IB** of ours. That IB holds the two
     `orbis_emit_eop` packets plus `orbis_pad_ib`, 256-byte aligned, from the existing private slice (one small
     IB per ring slot).
   - Verify the 4-dword slot really is a NOP(2) before overwriting it. If it is not, fall back to flattening
     for that submission and log why.
3. **Submit one dcb entry:** `dcb[0]` = the first chunk's IB address, `dcb_bytes[0]` = that IB's size. Keep
   `count=1`, which is the proven shape. Optionally prepend the existing `ORBIS_INIT_HW_STATE` block: put it in
   the fence-slot head and chain from there.
4. **Keep the switch:** `ORBIS_FLATTEN=1` restores today's flatten path exactly. Default: chain mode for every
   submission that **does not fit** the 2 MiB slot; flatten otherwise, at least for the first console runs.
   This keeps radeonsi/GL and small submissions byte-identical, so the GL risk stays near zero.
   - After the console proves chain mode, consider `ORBIS_CHAIN=always` and a smaller slot.
5. **Safety, all of it required:**
   - Patched IB tails are RADV-owned memory. RADV restores them itself (`cs_unchain`) on resubmit, but check
     the `SIMULTANEOUS_USE` case: a buffer pending on the GPU must not be repatched to a different target while
     it executes. With one in-order ring and a fence per submission, a resubmitted IB's previous patch is only
     overwritten after the slot wait. Make sure this holds, or refuse.
   - CPU writes to the patched dwords need `sceGnmFlushGarlic()` / `sfence` before submit. The existing code
     already does both before `sceGnmSubmitCommandBuffers`; make sure the patches happen before that.
   - The hung-dump ring (`orbis_hung_ring`) copies the flattened stream. In chain mode, store the IB list:
     addresses and sizes, plus the fence IB.
   - The always-on stream validator walks `dcb[0]` only. Extend it to walk the chain, read-only, bounded by
     `ORBIS_CHAIN_MAX_DEPTH`.
6. **The host arm** (`#if !defined(__PS4__)`) builds the same construction for the self-test. Add a self-test
   case for a chain longer than the slot. The flattener self-test lives near "THE FLATTENER HAS A SELF-TEST".

Optional, same build (cheap logging): for the first submission larger than 2 MiB, log the draw count and
dwords per draw: a packet-opcode histogram plus the top `SET_SH_REG` offsets. This answers why the title screen
is so large.

## Steps

1. **Write the patch.**
   - The Mesa fork source is `graphics-probe/build/sources/mesa` (git, pinned at `1a59cc1`). The checkout must
     stay clean; `build_mesa.py` applies `graphics-probe/patches/mesa/*.patch` in order and reverts them.
   - Edit, then run `git diff > graphics-probe/patches/mesa/0051-orbis-drm-chain-to-fence.patch` and
     `git checkout -- .`.
   - The main file is `src/amd/common/ac_orbis_drm.c`: the submit path around lines 9590–10080, and
     `orbis_build_flat` / `orbis_copy_ib` around 1286–1510.
2. **Build Mesa** (about 25 minutes; the user approved this build):
   `python3 graphics-probe/tools/build_mesa.py --sources graphics-probe/build/sources --sdk /home/wonder/PS4Toolchain --output graphics-probe/build/vulkan-mesa --radeonsi`.
   Never build Mesa from another tree at the same time; the worktree's `build/` is a symlink to main's.
3. **Relink and test.**
   - `python3 tools/build_platform_prx.py --build build/vulkan-mesa --output build/platform-vulkan`, run from
     `graphics-probe/`.
   - Then `python3 tests/test_vulkan_bridge.py`.
4. **Package the Vulkan bundle:**
   `python3 tools/package_graphics_bundle.py --platform build/platform-vulkan --facade-sdl build/lwjgl-facade-vulkan-3.4.3 --vma-sdl build/vma-3.4.3 --output build/graphics-bundle-vulkan`.
5. **Deploy, guarded.**
   - Use the session script
     `/tmp/claude-1000/-home-wonder-projects-jdk17u/d2db0724-7ff5-401a-b0d3-7c18bc6d3047/scratchpad/deploy_vulkan_bundle.py`.
     If it is gone, copy the logic: check that no JVM is running (dump-threads probe), check that no launch
     journal is pending, upload changed files with the manifest last, and read every file back.
   - Upload only to `/data/orbis-jdk/graphics-radeonsi-vulkan`. Never touch `graphics-radeonsi`. The console
     FTP is `172.20.10.4:2121`.
6. **Console runs, asked of the user one at a time** (minimal steps only):
   1. Minecraft 26.3, "Prefer Vulkan": the title screen should appear and stay. Pull the instance's
      `game/mesa.log` and `logs/latest.log`.
   2. A world on Vulkan: menus, pointer, a minute of play.
   3. A GL check on the same bundle (Graphics API → Default, or any older version). Confirm no regression.
7. **Commit only after the user confirms it works:**
   - The patch(es) and any tool or test changes, on `main` in `~/projects/orbis-launcher`.
   - End the commit message with the attribution lines. Never push to the public fork.
   - Then decide on 0050: drop it, or keep it as a permanent refusal trace.

## Rules from the user

- Ask before Mesa builds. This one is approved; later rebuilds need a new yes.
- Ask only for minimal test steps. Be quick with uploads.
- Never probe or profile a running game without asking.
- Commit only after the user confirms.
- Never enable `ORBIS_READ_REGS`.
- Uploads go through the guarded script.

---

# Addendum: dot-grid artefacts on Vulkan (PS4 Pro), after chain + fence works

User report: Minecraft 26.3 on Vulkan runs in a world, but shows a dotted / checkerboard grid over sky, clouds and
translucent areas. The F3 screen shows `improved-transparency`.

## Cause

Patch 0047's radeonsi-only hunk in `si_texture.c` was not ported:

- With the Pro's alternate tiling on (`ac_orbis_alt_tiling()`, the default in Neo mode), the DB writes depth
  through `DB_DEPTH_INFO` pipe config 18 (the shared `ac_descriptors.c` hunk of 0047). The texture unit reads the
  standard layout.
- Past 1024 pixels wide, the two disagree: "dot grids", per that hunk's own comment.
- radeonsi avoids it with `can_sample_z = can_sample_s = false`, sampling depth through a DB-to-CB flushed copy.
- RADV samples depth images directly, so any shader reading a ≥1024-wide depth buffer gets the grid.
- RADV gains nothing from the alternate depth layout: HTILE is off on Gladius for RADV (patch 0022).
- Before 0047 (Zink era) RADV used the standard layout and was clean.

## Step 1: confirm with no build

Add `-Dorbis.graphics.env.ORBIS_ALT_TILING=0` to `/data/orbis-jdk/runner/jvm-options.txt`. It is on the platform
env allowlist. Back the file up first and use the guarded upload.

The user launches 26.3 on Vulkan. Expected result: the grid is gone.

While this option is set, radeonsi (GL) also loses HTILE/HiZ (it is then off for GL by design), so remove the line
after the test.

## Step 2: fix (Mesa patch, new build, ask the user first)

Make the alternate `DB_DEPTH_INFO` override radeonsi-only:

- In `src/amd/common/ac_descriptors.c` (the 0047 hunk that ORs `S_02803C_PIPE_CONFIG(tile_mode >> 27)` and the
  bank fields when `info->family == CHIP_GLADIUS && ac_orbis_alt_tiling()`), add an input flag to the
  depth-surface state struct passed in, for example `bool orbis_alt_depth`. Apply the override only when it is set.
- radeonsi sets it true where it builds its DS surface state. RADV leaves it false (its DS path is
  `radv_initialise_ds_surface`, or wherever RADV calls `ac_init_ds_surface`). Then RADV's DB uses the standard
  layout the texture unit also reads, as in the Zink era.
- Use a per-call flag, not a process-wide global. The platform PRX contains both drivers, and a process can fall
  back from Vulkan to GL.
- Keep `addrlib enableAltTiling` and the tile tables as they are; colour is unaffected in radeonsi with them.

Alternative, only if step 2 is somehow insufficient: mirror radeonsi exactly. On Gladius with alternate tiling,
give RADV depth images with SAMPLED usage a decompress/copy path.

## Verify

- Vulkan: the grid is gone, in a world with improved transparency, and with Iris/shaders if available.
- GL (radeonsi) on the same bundle: HiZ is still on (the log line `radeonsi/orbis: HTILE allocated ...`), with
  no regression.

Commit only after the user confirms. The patch goes in `graphics-probe/patches/mesa/` as the next number.
