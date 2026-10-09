# Optional Minecraft Vulkan on Orbis

## Intent and acceptance

Expose Minecraft 26.3's built-in Vulkan renderer as an available device/backend on PS4, keeping OpenGL as the default and preserving saved renderer choices. Reuse the existing Mesa/RADV/VideoOut stack and account for the NEO fixes in the working radeonsi build. The user authorized implementation after planning without waiting for further design approval.

Acceptance: LWJGL loads a real Orbis Vulkan entry point, enumerates the PS4 device, SDL creates a Vulkan surface, VMA allocates through Vulkan function pointers, and the renderer can acquire/present through the existing VideoOut WSI. Host tests and target binary audits precede hardware acceptance; hardware rendering cannot be claimed without a console run.

## Architecture

Expose `vkGetInstanceProcAddr` from the existing Mesa platform PRX as a direct RADV dispatch wrapper. Point LWJGL's Vulkan library setting to that same PRX in Vulkan-capable bundle profiles. This avoids a second statically linked driver, memory arena, thread/runtime environment, and VideoOut owner. Use `VK_KHR_surface` and `VK_EXT_headless_surface`, whose Orbis WSI already presents on the console. SDL's loader must return the same entry point LWJGL resolves, not a dummy pointer or Linux loader.

Add a no-OpenGL-context window path through the existing GLFW/SDL facade and platform bridge. Keep window/input management shared. Vulkan surfaces use `vkCreateHeadlessSurfaceEXT` with the caller's instance and allocation callbacks; presentation support verifies a real graphics queue. OpenGL and Vulkan windows cannot simultaneously own the single console scanout. Failures return SDL errors and native stage diagnostics; never silently substitute an OpenGL surface.

Build the official pinned LWJGL 3.4.3 VMA JNI sources against the pinned Vulkan headers using the existing PRX runtime recipe, dynamic Vulkan function pointers, and no Linux libraries. Register the VMA PRX in the 26.3 bundle. Do not claim support for older Minecraft Vulkan integrations in this change.

## NEO driver work

Inventory existing patches into shared AMD/DRM/addrlib fixes, RADV fixes already present, and radeonsi-only fixes with a RADV equivalent. Shared fixes (NEO topology, tile tables, alternate tiling, descriptor depth layout, raster config) already apply to both drivers. Port the Gladius colour-metadata safety rule to RADV. Keep RADV's existing conservative HTILE workaround until its alternate-tiling path is validated. Audit vertex-fetch and ring-buffer workarounds for applicability rather than mechanically copying Gallium code. Preserve Base-model behavior.

## Selection and rollout

Do not set Minecraft's preferred backend or rewrite `options.txt`. OpenGL remains the default. Bundle availability enables Minecraft's ordinary Vulkan selection. Test and package into separate output directories, preserve deployment backups, and do not switch a running instance's renderer unattended. A dedicated Vulkan probe provides device/surface/swapchain evidence before a manual Minecraft selection.

## Verification boundaries

Host tests exercise SDL signatures, buffer ownership, instance/surface/queue forwarding, failed native operations, no-API windows, and fallback lifecycle. Target audits enforce JNI exports, Vulkan entry-point export, pinned VMA ABI, and no unresolved non-system imports. Preserve the four documented pre-existing full-suite failures instead of broadening this task into unrelated fixes. A static capability check and driver probe distinguish implemented APIs from Vulkan requirements the GPU does not support. Any remaining hardware-only validation is reported explicitly.
