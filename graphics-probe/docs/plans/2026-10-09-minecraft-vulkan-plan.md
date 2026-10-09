# Optional Minecraft Vulkan Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement inline, with one whole-branch review.

**Goal:** Make Minecraft 26.3 Vulkan available on PS4 without changing the default renderer.
**Architecture:** One Mesa PRX owns RADV and EGL. SDL bridges Vulkan to the existing headless/VideoOut WSI. Pinned VMA JNI is a separate lightweight PRX using Vulkan function pointers.
**Tech Stack:** Java 17 facade bytecode, JNI C/C++, Mesa/RADV, SDL3 API, OpenOrbis PRX toolchain, Python build/audit tests.
**Spec:** `graphics-probe/docs/plans/2026-10-09-minecraft-vulkan-design.md`

## Global Constraints

- OpenGL remains the default; preserve saved renderer choices and options.txt.
- Minecraft 26.3 / LWJGL 3.4.3 is the initial supported Vulkan profile.
- Reuse the existing driver instance, Orbis runtime, memory arena and VideoOut WSI.
- No Linux native libraries or fabricated Vulkan features.
- Preserve Base-model behavior; carry applicable NEO workarounds.
- Keep target artifacts separate until audits pass; report hardware acceptance honestly.

## Review Focus

- Vulkan probing before window creation must not acquire scanout or alter GL defaults.
- Vulkan/GL fallback must release the single native window and context without double destroy.
- Surface allocator callbacks and caller buffer positions must be preserved.
- Native errors and missing entry points must fail clearly rather than return fake success.
- RADV metadata behavior on Gladius must reflect measured safety workarounds.

### Task 1: Native Vulkan entry point and no-API window lifecycle

**Files:** Modify `graphics-probe/lwjgl/platform/orbis_lwjgl_platform.c`, `graphics-probe/lwjgl/java/orbis/graphics/NativePlatform.java`, `graphics-probe/tools/build_platform_prx.py`; create `graphics-probe/lwjgl/platform/orbis_vulkan.c`, native bridge host tests and contract tests.
**Interfaces:** Export `vkGetInstanceProcAddr(VkInstance,const char*)`; JNI `nCreateVulkanWindow()J`, `nVulkanCreateSurface(JJJ)I`, `nVulkanPresentationSupport(JJI)Z`. Existing GL natives and errors remain compatible.
- [ ] Write host tests for actual direct dispatch, allocation-callback forwarding, failed surface creation, valid/invalid queues, no-API lifecycle.
- [ ] Run tests and verify expected failures before implementation.
- [ ] Implement bridges over RADV dispatch; support a reserved no-context window and fixed framebuffer dimensions.
- [ ] Build the target PRX against the existing radeonsi Mesa output and enforce export/JNI counts.
- [ ] Verify host tests and target audits; commit the independently testable bridge.

### Task 2: SDL Vulkan facade and optional bundle integration

**Files:** Modify `OrbisSDL.java`, `OrbisGLFW.java`, `build_lwjgl_facade.py`, `FacadeSdlTest.java`, host native stand-in, `GpuLaunch.java`, graphics bundle packaging/tests; create `build_vma.py` and VMA contract tests.
**Interfaces:** SDL `SDL_Vulkan_LoadLibrary(CharSequence)Z`, `SDL_Vulkan_GetVkGetInstanceProcAddr()J`, `SDL_Vulkan_GetInstanceExtensions()PointerBuffer`, `SDL_Vulkan_GetPresentationSupport(VkInstance,VkPhysicalDevice,int)Z`, `SDL_Vulkan_CreateSurface(long,VkInstance,VkAllocationCallbacks,LongBuffer)Z`; Vulkan window flag `0x10000000`, GLFW_NO_API.
- [ ] Write tests for loader identity, real no-API windows, output positions, native failure, teardown/recreate, and unchanged default renderer.
- [ ] Observe failures; implement delegations with real instance pointers and persistent extension name memory.
- [ ] Build pinned VMA JNI with `VMA_STATIC_VULKAN_FUNCTIONS=0` and `VMA_DYNAMIC_VULKAN_FUNCTIONS=0`; enforce all official JNI methods.
- [ ] Register VMA and configure LWJGL's Vulkan library property only for capable profiles. Do not set a backend preference.
- [ ] Run 3.4.3 facade tests, VMA tests and native builds; commit.

### Task 3: NEO parity, Vulkan device probe and acceptance artifacts

**Files:** Add focused Mesa patch(es), tests for NEO metadata behavior, a Java Vulkan probe, probe builder and Vulkan integration documentation.
**Interfaces:** RADV Gladius image metadata safety and existing AMD common fixes; a probe prints entry point, device/API/features, real surface and presentation outcomes.
- [ ] Inventory the existing patch chain and record which fixes are shared versus RADV-specific.
- [ ] Write failing tests for the selected RADV safety change, apply the smallest patch, rebuild using the pinned source and existing patch recipe.
- [ ] Build and audit the updated platform PRX, VMA, facade, runner and bundle in separate outputs.
- [ ] Run a host Vulkan loader/surface probe where possible; package a PS4 acceptance probe. Use console results only when actually obtained.
- [ ] Run the full graphics suite and report all failures by name; retain test/target audit evidence.
- [ ] Request one fresh whole-branch review, fix material findings, commit and document optional selection and deployment/rollback steps.
