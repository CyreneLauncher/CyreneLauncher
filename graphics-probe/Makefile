PROJECT_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
SDK ?= /home/wonder/PS4Toolchain
PKGTOOL ?= /home/wonder/pkgtool-net8/PkgTool.Core.dll
DOTNET ?= dotnet
PYTHON ?= python3

.PHONY: package graphics-bundle lwjgl-smoke lwjgl-compat minecraft-prepare repositories-verify repositories-bootstrap
repositories-verify:
	$(PYTHON) $(PROJECT_DIR)/tools/repositories.py verify

repositories-bootstrap:
	$(PYTHON) $(PROJECT_DIR)/tools/repositories.py bootstrap $(if $(SEED_ROOT),--seed-root $(SEED_ROOT))

package:
	bash $(PROJECT_DIR)/tools/build_probe.sh $(PROJECT_DIR)/build/target $(SDK)
	$(PYTHON) $(PROJECT_DIR)/tools/package_probe.py --sdk $(SDK) --pkgtool $(PKGTOOL) --dotnet $(DOTNET)

# Optional runner graphics bundle (display=gpu). Requires the Mesa target build, the pinned
# LWJGL/dyncall archives in build/downloads (or FETCH=--fetch) and the official LWJGL 3.2.2
# Java JARs in LWJGL_JARS. Builds local files only; copying them to a console is manual.
ORBIS_SDK ?= /home/wonder/ps4-payload-sdk
LWJGL_JARS ?= /tmp/lwjgl-audit
FETCH ?=
graphics-bundle:
	$(PYTHON) $(PROJECT_DIR)/tools/build_platform_prx.py --sdk $(ORBIS_SDK) --toolchain $(SDK)
	$(PYTHON) $(PROJECT_DIR)/tools/build_lwjgl_natives.py --sdk $(ORBIS_SDK) --toolchain $(SDK) $(FETCH)
	$(PYTHON) $(PROJECT_DIR)/tools/build_openal.py --sdk $(ORBIS_SDK) --toolchain $(SDK) $(FETCH)
	$(PYTHON) $(PROJECT_DIR)/tools/build_lwjgl_optional.py --sdk $(ORBIS_SDK) --toolchain $(SDK) --jars $(LWJGL_JARS)
	$(PYTHON) $(PROJECT_DIR)/tools/build_lwjgl_facade.py --jars $(LWJGL_JARS) $(FETCH)
	$(PYTHON) $(PROJECT_DIR)/tools/build_launcher.py --jars $(LWJGL_JARS)
	$(PYTHON) $(PROJECT_DIR)/tools/package_graphics_bundle.py

# Runner-launched LWJGL smoke test files (build/lwjgl-smoke/; manual steps in the generated instructions).
lwjgl-smoke:
	$(PYTHON) $(PROJECT_DIR)/tools/build_lwjgl_smoke.py --jars $(LWJGL_JARS)

# Independent runner-launched STB/optional/GLFW/GL callback contract file set.
lwjgl-compat:
	$(PYTHON) $(PROJECT_DIR)/tools/build_lwjgl_compat.py --jars $(LWJGL_JARS)

# Official Minecraft 1.18.2 host-only file tree; it is never copied to a console automatically.
MINECRAFT_USERNAME ?= OrbisOffline
MINECRAFT_OUTPUT ?= $(PROJECT_DIR)/build/minecraft-1.18.2
MINECRAFT_CACHE ?= $(PROJECT_DIR)/build/downloads/minecraft
minecraft-prepare:
	$(PYTHON) $(PROJECT_DIR)/tools/prepare_minecraft.py --version 1.18.2 --output $(MINECRAFT_OUTPUT) --cache $(MINECRAFT_CACHE) --username $(MINECRAFT_USERNAME)

# Native AMD graphics bundle: radeonsi for OpenGL and RADV for Vulkan.
# Minecraft's saved backend preference selects the API; Zink is not built.
# Requires the pinned Mesa build and the existing component builds for all bundle profiles.
LWJGL_343_JARS ?= /tmp/lwjgl-3.4.3
VULKAN_MESA_BUILD ?= $(PROJECT_DIR)/build/vulkan-mesa
.PHONY: vulkan-bundle vulkan-probe
vulkan-bundle:
	$(PYTHON) $(PROJECT_DIR)/tools/build_platform_prx.py --build $(VULKAN_MESA_BUILD) --output $(PROJECT_DIR)/build/platform-vulkan --sdk $(ORBIS_SDK) --toolchain $(SDK)
	$(PYTHON) $(PROJECT_DIR)/tools/build_vma.py --jars $(LWJGL_343_JARS) --sdk $(ORBIS_SDK) --toolchain $(SDK) $(FETCH)
	$(PYTHON) $(PROJECT_DIR)/tools/build_lwjgl_facade.py --release 3.4.3 --jars $(LWJGL_343_JARS) --output $(PROJECT_DIR)/build/lwjgl-facade-vulkan-3.4.3
	$(PYTHON) $(PROJECT_DIR)/tools/package_graphics_bundle.py --platform $(PROJECT_DIR)/build/platform-vulkan --facade-sdl $(PROJECT_DIR)/build/lwjgl-facade-vulkan-3.4.3 --vma-sdl $(PROJECT_DIR)/build/vma-3.4.3 --output $(PROJECT_DIR)/build/graphics-bundle-native
vulkan-probe:
	$(PYTHON) $(PROJECT_DIR)/tools/build_vulkan_probe.py --jars $(LWJGL_343_JARS)
