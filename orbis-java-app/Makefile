# The complete launcher PKG consumes verified JDK/native exports; it does not rebuild those ports.
PROJECT_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
SDK ?= /home/wonder/ps4-payload-sdk
TOOLCHAIN ?= /home/wonder/PS4Toolchain
# A host JDK 17: javac for app.jar (--release 17, so JDK 17 and 21 runtimes both load it) and the
# JNI headers the eboot compiles against (the JNI invocation API both runtimes export).
BOOT_JDK ?= /usr/lib/jvm/java-17-openjdk-amd64
JNI_HEADERS ?= $(BOOT_JDK)/include
JNI_PLATFORM ?= linux
PYTHON ?= python3
DOTNET ?= dotnet
PKGTOOL ?= /home/wonder/pkgtool-net8/PkgTool.Core.dll
CC = $(SDK)/bin/orbis-clang
CXX = $(SDK)/bin/orbis-clang++
LD = $(SDK)/bin/orbis-ld
FSELF ?= $(TOOLCHAIN)/bin/linux/create-fself
TITLE ?= Orbis Launcher
VERSION ?= 01.05
TITLE_ID ?= OJDK00003
CONTENT_ID ?= IV0000-OJDK00003_00-ORBISJARRUNNER01
PAID ?= 0x3800000000000011
# param.sfo ATTRIBUTE bits (LibOrbisPkg SfoData.AttributeNames, from psdevwiki): 0x00800000 is
# "The application supports NEO mode (PS4 pro)" - on a PS4 Pro the title then runs in Neo mode
# (36 CUs, higher GPU and CPU clocks) instead of Base mode. ATTRIBUTE=0 builds a Base-mode package.
# 0x00010000 is "CPU mode (7 CPU)": a seventh CPU core for the application.
# 0x00000020 is "Enter Button Assignment for the common dialog: Assigned by the System Software": the
# system keyboard (sceImeDialog) confirms with the console's own enter button. Without it (or 0x2,
# Cross) the dialogs used Circle to confirm and Cross to cancel.
ATTRIBUTE ?= 0x00810020
# param.sfo ATTRIBUTE2 bits (SfoData.Attribute2Names): 0x00004000 is "Enlarge FMEM 256MB", 256 MiB more
# flexible memory - every anonymous mapping is charged to it. Asking for more in the executable's own
# memory parameters instead is refused at load (PRX_INVALID_IMAGE).
ATTRIBUTE2 ?= 0x00004000
BUILD := $(PROJECT_DIR)/build-selfcontained
STAGE := $(BUILD)/stage
OUT := $(BUILD)/output
CLASSES := $(BUILD)/app-classes
TARGET_FLAGS := --target=x86_64-pc-freebsd12-elf -fPIC -funwind-tables -Wno-writable-strings -isysroot $(TOOLCHAIN) -isystem $(TOOLCHAIN)/include
APP_SOURCES := $(wildcard $(PROJECT_DIR)/src/runner/*.java $(PROJECT_DIR)/src/runner/gpu/*.java $(PROJECT_DIR)/src/runner/lwjgl/*.java)

.PHONY: all runner package runtime-stage check check-artifacts clean FORCE
all: package
FORCE:
# Compare content to track command-line settings without rebuilding on every make.
$(BUILD)/settings: FORCE
	@mkdir -p $(BUILD)
	@printf '%s\n' '$(VERSION)|$(TITLE)|$(TITLE_ID)|$(CONTENT_ID)|$(PAID)|$(ATTRIBUTE)|$(ATTRIBUTE2)|$(SDK)|$(TOOLCHAIN)|$(BOOT_JDK)|$(JNI_HEADERS)|$(JNI_PLATFORM)|$(CC)|$(CXX)|$(LD)|$(FSELF)|$(PKGTOOL)|$(TARGET_FLAGS)|$(DOTNET)' > $@.tmp
	@sha256sum $(PROJECT_DIR)/Makefile >> $@.tmp
	@cmp -s $@.tmp $@ && rm $@.tmp || mv $@.tmp $@
$(BUILD)/classes.stamp: $(APP_SOURCES) $(BUILD)/settings
	mkdir -p $(CLASSES)
	$(BOOT_JDK)/bin/javac --release 17 -d $(CLASSES) $(APP_SOURCES)
	touch $@
$(BUILD)/app.jar: $(BUILD)/classes.stamp
	$(BOOT_JDK)/bin/jar --create --file $@ -C $(CLASSES) .
$(BUILD)/launcher_profile.h: $(PROJECT_DIR)/tools/write_launcher_profile.py $(BUILD)/settings
	$(PYTHON) $< --output $@ --main-class runner/Main --user-dir /data/orbis-jdk/runner --log-path /data/orbis-jdk/logs/orbis-runner.log --tmp-dir /data/orbis-jdk/runner/tmp --runtime-jvm-options /data/orbis-jdk/runner/jvm-options.txt --screen-console --java-option=-Dsun.nio.fs.chdirAllowed=true --java-option=-XX:HeapBaseMinAddress=4g
$(BUILD)/launcher.o: $(PROJECT_DIR)/launcher.c $(PROJECT_DIR)/tools/runner_config.h $(PROJECT_DIR)/tools/orbis_stat.h $(PROJECT_DIR)/tools/orbis_runtime.h $(PROJECT_DIR)/tools/orbis_mapping_probe.h $(PROJECT_DIR)/tools/orbis_screen_console.h $(PROJECT_DIR)/tools/orbis_input_service.h $(PROJECT_DIR)/tools/orbis_display_service.h $(PROJECT_DIR)/tools/orbis_desktop_bridge.h $(PROJECT_DIR)/tools/orbis_gpu_input_bridge.h $(PROJECT_DIR)/tools/orbis_desktop_preflight.h $(BUILD)/launcher_profile.h $(BUILD)/settings
	$(CXX) $(TARGET_FLAGS) -x c++ -DORBIS_JAVA_BUILD_VERSION=\"$(VERSION)\" -DORBIS_JAVA_BUILD_KIND=\"launcher-installed\" -I$(BUILD) -I$(JNI_HEADERS) -I$(JNI_HEADERS)/$(JNI_PLATFORM) -c $< -o $@
$(BUILD)/%.o: $(PROJECT_DIR)/tools/%.c $(BUILD)/settings
	$(CC) $(TARGET_FLAGS) -MMD -MP -c $< -o $@
$(BUILD)/orbis_screen_console.o: $(PROJECT_DIR)/tools/orbis_screen_console.cpp $(PROJECT_DIR)/tools/orbis_screen_console.h $(PROJECT_DIR)/tools/orbis_screen_font.h $(PROJECT_DIR)/tools/orbis_input_service.h $(PROJECT_DIR)/tools/orbis_display_service.h $(BUILD)/settings
	$(CXX) $(TARGET_FLAGS) -MMD -MP -c $< -o $@
$(BUILD)/orbis_display_service.o: $(PROJECT_DIR)/tools/orbis_display_service.cpp $(PROJECT_DIR)/tools/orbis_display_service.h $(PROJECT_DIR)/tools/orbis_input_service.h $(BUILD)/settings
	$(CXX) $(TARGET_FLAGS) -MMD -MP -c $< -o $@
$(BUILD)/orbis_input_service.o: $(PROJECT_DIR)/tools/orbis_input_service.cpp $(PROJECT_DIR)/tools/orbis_input_service.h $(PROJECT_DIR)/tools/orbis_mouse_abi.h $(PROJECT_DIR)/tools/orbis_display_service.h $(BUILD)/settings
	$(CXX) $(TARGET_FLAGS) -MMD -MP -c $< -o $@
$(BUILD)/orbis_desktop_bridge.o: $(PROJECT_DIR)/tools/orbis_desktop_bridge.cpp $(PROJECT_DIR)/tools/orbis_desktop_bridge.h $(PROJECT_DIR)/tools/orbis_display_service.h $(PROJECT_DIR)/tools/orbis_input_service.h $(PROJECT_DIR)/tools/orbis_input_packet.h $(BUILD)/settings
	$(CXX) $(TARGET_FLAGS) -MMD -MP -I$(JNI_HEADERS) -I$(JNI_HEADERS)/$(JNI_PLATFORM) -c $< -o $@
$(BUILD)/orbis_gpu_input_bridge.o: $(PROJECT_DIR)/tools/orbis_gpu_input_bridge.cpp $(PROJECT_DIR)/tools/orbis_gpu_input_bridge.h $(PROJECT_DIR)/tools/orbis_input_service.h $(PROJECT_DIR)/tools/orbis_input_packet.h $(BUILD)/settings
	$(CXX) $(TARGET_FLAGS) -MMD -MP -I$(JNI_HEADERS) -I$(JNI_HEADERS)/$(JNI_PLATFORM) -c $< -o $@
$(BUILD)/orbis_desktop_preflight.o: $(PROJECT_DIR)/tools/orbis_desktop_preflight.cpp $(PROJECT_DIR)/tools/orbis_desktop_preflight.h $(PROJECT_DIR)/tools/runner_config.h $(BUILD)/settings
	$(CXX) $(TARGET_FLAGS) -MMD -MP -c $< -o $@
-include $(wildcard $(BUILD)/*.d)
$(BUILD)/launcher.elf: $(BUILD)/orbis_desktop_bridge.o $(BUILD)/orbis_gpu_input_bridge.o $(BUILD)/orbis_desktop_preflight.o $(BUILD)/launcher.o $(BUILD)/runner_config.o $(BUILD)/orbis_runtime.o $(BUILD)/orbis_mapping_probe.o $(BUILD)/orbis_screen_console.o $(BUILD)/orbis_display_service.o $(BUILD)/orbis_input_service.o
	$(LD) -m elf_x86_64 -pie --script $(TOOLCHAIN)/link.x --eh-frame-hdr -L$(TOOLCHAIN)/lib --rpath=/app0 --wrap=_exit -o $@ $^ $(TOOLCHAIN)/lib/crt1.o -lc -lkernel -lc++ -lSceVideoOut -lSceGnmDriver -lSceKeyboard -lSceMouse -lSceSysmodule -lSceUserService
$(STAGE)/eboot.bin: $(BUILD)/launcher.elf
	mkdir -p $(STAGE)
	$(FSELF) -in=$< -out=$(BUILD)/launcher.oelf --eboot $@ --paid $(PAID)
$(STAGE)/app.jar: $(BUILD)/app.jar
	mkdir -p $(STAGE)
	cp $< $@
$(STAGE)/runner/README.txt: $(PROJECT_DIR)/defaults/README.txt
	mkdir -p $(dir $@)
	cp $< $@
runner: $(STAGE)/eboot.bin $(STAGE)/app.jar $(STAGE)/runner/README.txt
$(STAGE)/sce_sys/param.sfo: $(BUILD)/settings
	mkdir -p $(STAGE)/sce_sys/about $(STAGE)/sce_module
	cp $(TOOLCHAIN)/samples/hello_world/sce_sys/icon0.png \
		$(STAGE)/sce_sys/icon0.png
	cp $(TOOLCHAIN)/samples/hello_world/sce_sys/about/right.sprx \
		$(STAGE)/sce_sys/about/right.sprx
	cp $(TOOLCHAIN)/samples/hello_world/sce_module/libc.prx \
		$(STAGE)/sce_module/libc.prx
	cp $(TOOLCHAIN)/samples/hello_world/sce_module/libSceFios2.prx \
		$(STAGE)/sce_module/libSceFios2.prx
	$(DOTNET) $(PKGTOOL) sfo_new $@
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ APP_TYPE --type Integer --maxsize 4 --value 1
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ APP_VER --type Utf8 --maxsize 8 --value '$(VERSION)'
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ ATTRIBUTE --type Integer --maxsize 4 --value $$(($(ATTRIBUTE)))
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ ATTRIBUTE2 --type Integer --maxsize 4 --value $$(($(ATTRIBUTE2)))
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ CATEGORY --type Utf8 --maxsize 4 --value gd
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ CONTENT_ID --type Utf8 --maxsize 48 --value '$(CONTENT_ID)'
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ DOWNLOAD_DATA_SIZE --type Integer --maxsize 4 --value 0
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ SYSTEM_VER --type Integer --maxsize 4 --value 0
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ TITLE --type Utf8 --maxsize 128 --value '$(TITLE)'
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ TITLE_ID --type Utf8 --maxsize 12 --value '$(TITLE_ID)'
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ VERSION --type Utf8 --maxsize 8 --value '$(VERSION)'

RELEASE_INPUTS := $(PROJECT_DIR)/tools/runtime-package-inputs.json
RELEASE_LAUNCHER := $(PROJECT_DIR)/../graphics-probe/build/launcher-release/orbis-launcher.jar
RELEASE_JARS := $(PROJECT_DIR)/../graphics-probe/build/downloads/runner-jars
runtime-stage: runner $(STAGE)/sce_sys/param.sfo
	$(PYTHON) $(PROJECT_DIR)/../graphics-probe/tools/repositories.py verify
	$(PYTHON) $(PROJECT_DIR)/tools/runtime_package.py prepare-libraries --inputs $(RELEASE_INPUTS) --library-output $(RELEASE_JARS)
	$(PYTHON) $(PROJECT_DIR)/../graphics-probe/tools/build_launcher.py --jars $(RELEASE_JARS) --gson $(RELEASE_JARS)/gson-2.8.9.jar --output $(dir $(RELEASE_LAUNCHER))
	$(PYTHON) $(PROJECT_DIR)/tools/runtime_package.py assemble --inputs $(RELEASE_INPUTS) --stage $(STAGE) --launcher-jar $(RELEASE_LAUNCHER)

$(STAGE)/package.gp4: runtime-stage $(PROJECT_DIR)/tools/make_gp4.py
	$(PYTHON) $(PROJECT_DIR)/tools/check_runner_package.py $(STAGE)
	$(PYTHON) $(PROJECT_DIR)/tools/make_gp4.py --root $(STAGE) --out $@ --content-id $(CONTENT_ID)
package: $(STAGE)/package.gp4
	$(PYTHON) $(PROJECT_DIR)/tools/check_runner_package.py $(STAGE)
	mkdir -p $(OUT)
	$(DOTNET) $(PKGTOOL) pkg_build $< $(OUT)
	$(DOTNET) $(PKGTOOL) pkg_validate --verbose $(OUT)/$(CONTENT_ID).pkg

check:
	cd $(PROJECT_DIR) && PYTHONDONTWRITEBYTECODE=1 $(PYTHON) tests/run_checks.py --sdk $(SDK) --toolchain $(TOOLCHAIN)
check-artifacts:
	cd $(PROJECT_DIR) && $(PYTHON) tests/test_orbis_runtime_binary.py $(BUILD)/launcher.elf
clean:
	rm -rf $(BUILD)

CACIO_SOURCE := $(PROJECT_DIR)/build-cacio/source
CACIO_EXPORT := $(PROJECT_DIR)/build-cacio/export
CACIO_INPUTS := $(wildcard $(PROJECT_DIR)/tools/cacio-source.json $(PROJECT_DIR)/tools/build_cacio.py $(PROJECT_DIR)/cacio/patches/* $(shell find $(PROJECT_DIR)/src-desktop -type f -name '*.java' 2>/dev/null))
.PHONY: cacio-source cacio-export
cacio-source:
	$(PYTHON) $(PROJECT_DIR)/tools/build_cacio.py --prepare-source
cacio-export: $(CACIO_INPUTS)
	$(PYTHON) $(PROJECT_DIR)/tools/build_cacio.py --source $(CACIO_SOURCE) --output $(CACIO_EXPORT) --jdk $(BOOT_JDK)
