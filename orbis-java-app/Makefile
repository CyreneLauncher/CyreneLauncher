PROJECT_DIR := $(abspath .)

PROFILE ?= probe

JDK_CONF ?= orbis-x86_64-server-fastdebug

SDK       ?= /home/wonder/ps4-payload-sdk
TOOLCHAIN ?= /home/wonder/PS4Toolchain
BOOT_JDK  ?= /tmp/jdk17-orbis
JDK_IMAGE ?= /home/wonder/projects/jdk17u/build/$(JDK_CONF)/jdk
JDK_SUPPORT ?= $(abspath $(JDK_IMAGE)/../support)
JMOD      ?= $(BOOT_JDK)/bin/jmod
JLINK     ?= $(BOOT_JDK)/bin/jlink
RUNTIME_MODULES ?= java.base,java.logging,java.xml,java.net.http,java.transaction.xa,java.sql,java.naming,java.security.jgss,java.security.sasl,java.sql.rowset,java.xml.crypto,java.scripting,java.rmi,jdk.charsets,jdk.localedata,jdk.random,jdk.unsupported,jdk.zipfs,java.compiler,jdk.httpserver,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.nio.mapmode,jdk.security.auth,jdk.security.jgss,jdk.xml.dom,jdk.dynalink
COMPILE_MODULES ?= java.compiler,jdk.httpserver,jdk.net,jdk.nio.mapmode,jdk.security.auth,jdk.security.jgss,jdk.xml.dom,jdk.dynalink
RUNTIME_VERSION ?= 17.0.20
DOTNET    ?= dotnet
PKGTOOL   ?= /home/wonder/pkgtool-net8/PkgTool.Core.dll

TITLE      ?= Orbis Java 17
VERSION    ?= 2.06
TITLE_ID   ?= OJDK00001
CONTENT_ID ?= IV0000-OJDK00001_00-ORBISJDKAPP00001
PAID       ?= 0x3800000000000011

APP_MAIN_CLASS ?= demo/Main
APP_USER_DIR ?= /app0
APP_LOG_PATH ?= /data/orbis-jdk/logs/orbis-java.log
APP_TMP_DIR ?= /data/orbis-jdk/tmp
APP_SOURCES ?= src/demo/Main.java
APP_PROFILE_JAVA_OPTIONS ?= -Dsun.misc.URLClassPath.debug=true

PAPERCLIP_JAR ?= /home/wonder/paper-1.18.2-388.jar
PAPERCLIP_SHA256 ?= 0578f18f4d632b494b468ec56b3b414b5b56fea087ee7d39cf6dcdf4c9d01f05
PAPER_SERVER_SHA256 ?= c1eb29c996952a6c8ff8bb53d223335394fbf396c40a19153db7897233319db2
PAPER_LIBRARY_COUNT ?= 76
PAPER_EXPANDED_ROOT ?= $(abspath build-paper/paper-expanded/cache)

CC         := $(SDK)/bin/orbis-clang
CXX        := $(SDK)/bin/orbis-clang++
LD         := $(SDK)/bin/orbis-ld
FSELF      := $(TOOLCHAIN)/bin/linux/create-fself
CREATE_GP4 := $(TOOLCHAIN)/bin/linux/create-gp4
PYTHON    ?= python3

BUILD      := build
CLASSES    := $(BUILD)/app-classes
STAGE      := $(BUILD)/stage
OUT        := $(BUILD)/output

ifeq ($(PROFILE),paper)
JDK_CONF := orbis-x86_64-paper-fastdebug
JDK_IMAGE := /home/wonder/projects/jdk17u/build/$(JDK_CONF)/jdk
JDK_SUPPORT := $(abspath $(JDK_IMAGE)/../support)
TITLE := Orbis Paper 1.18.2
VERSION := 0.01
TITLE_ID := OJDK00002
CONTENT_ID := IV0000-OJDK00002_00-ORBISPAPER018020
BUILD := build-paper
CLASSES := $(BUILD)/app-classes
STAGE := $(BUILD)/stage
OUT := $(BUILD)/output
APP_MAIN_CLASS := paperbootstrap/Main
APP_USER_DIR := /data/orbis-jdk/paper
APP_LOG_PATH := /data/orbis-jdk/logs/orbis-paper.log
APP_TMP_DIR := /data/orbis-jdk/paper/tmp
APP_SOURCES := src/paperbootstrap/Main.java
APP_PROFILE_JAVA_OPTIONS := -Djava.awt.headless=true,-Dsun.nio.fs.chdirAllowed=true,-Dio.netty.transport.noNative=true,-Dio.netty.noUnsafe=true,-Dorg.jline.terminal.dumb=true,-Dorbis.paper.root=/app0/paper,-Djna.tmpdir=/data/orbis-jdk/paper/native,-Dio.netty.native.workdir=/data/orbis-jdk/paper/native,-Dorg.sqlite.tmpdir=/data/orbis-jdk/paper/native
RUNTIME_MODULES := java.base,java.logging,java.xml,java.net.http,java.transaction.xa,java.sql,java.naming,java.security.jgss,java.security.sasl,java.sql.rowset,java.xml.crypto,java.scripting,java.rmi,jdk.charsets,jdk.localedata,jdk.random,jdk.unsupported,jdk.zipfs,java.compiler,jdk.httpserver,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.nio.mapmode,jdk.security.auth,jdk.security.jgss,jdk.xml.dom,jdk.dynalink,java.datatransfer,java.desktop,java.management,java.prefs,jdk.crypto.ec,jdk.jfr
COMPILE_MODULES := java.base
endif

TARGET_FLAGS := --target=x86_64-pc-freebsd12-elf \
                -fPIC -funwind-tables \
                -Wno-writable-strings \
                -isysroot $(TOOLCHAIN) \
                -isystem $(TOOLCHAIN)/include

comma := ,
PROFILE_OPTION_ARGS = $(foreach option,$(subst $(comma), ,$(APP_PROFILE_JAVA_OPTIONS)),--java-option=$(option))

.PHONY: all images package clean

all: package

images: package

$(BUILD)/classes.stamp: $(APP_SOURCES)
	mkdir -p $(CLASSES)
	$(BOOT_JDK)/bin/javac --release 17 --add-modules $(COMPILE_MODULES) -d $(CLASSES) $(APP_SOURCES)
	touch $@

$(BUILD)/app.jar: $(BUILD)/classes.stamp
	$(BOOT_JDK)/bin/jar --create --file $@ -C $(CLASSES) .

$(BUILD)/launcher_profile.h: Makefile tools/write_launcher_profile.py
	mkdir -p $(BUILD)
	$(PYTHON) tools/write_launcher_profile.py --output $@ \
		--main-class '$(APP_MAIN_CLASS)' --user-dir '$(APP_USER_DIR)' \
		--log-path '$(APP_LOG_PATH)' --tmp-dir '$(APP_TMP_DIR)' \
		$(PROFILE_OPTION_ARGS)

$(BUILD)/launcher.o: launcher.c tools/orbis_runtime.h $(BUILD)/launcher_profile.h Makefile
	mkdir -p $(BUILD)
	$(CXX) $(TARGET_FLAGS) \
		-DORBIS_JAVA_BUILD_VERSION=\"$(VERSION)\" \
		-I$(BUILD) \
		-I$(JDK_IMAGE)/include \
		-I$(JDK_IMAGE)/include/orbis \
		-c $< -o $@

$(BUILD)/orbis_runtime.o: tools/orbis_runtime.c tools/orbis_runtime.h
	mkdir -p $(BUILD)
	$(CC) $(TARGET_FLAGS) -c $< -o $@

$(BUILD)/launcher.elf: $(BUILD)/launcher.o $(BUILD)/orbis_runtime.o
	$(LD) -m elf_x86_64 -pie \
		--script $(TOOLCHAIN)/link.x \
		--eh-frame-hdr \
		-L$(JDK_IMAGE)/lib/server \
		-L$(TOOLCHAIN)/lib \
		--rpath=/app0 \
		--rpath=/app0/java/lib/server \
		--rpath=/app0/java/lib \
		--wrap=_exit \
		-o $@ $^ \
		$(TOOLCHAIN)/lib/crt1.o \
		-lc -lkernel -lc++

$(STAGE)/eboot.bin: $(BUILD)/launcher.elf
	mkdir -p $(STAGE)
	$(FSELF) -in=$< -out=$(BUILD)/launcher.oelf \
		--eboot $@ --paid $(PAID) \
		--library-path $(JDK_IMAGE)/lib/server:$(JDK_IMAGE)/lib

$(STAGE)/app.jar: $(BUILD)/app.jar
	mkdir -p $(STAGE)
	cp $< $@

ifeq ($(PROFILE),paper)
$(PAPER_EXPANDED_ROOT)/.stamp: $(PAPERCLIP_JAR)
	mkdir -p $(dir $(PAPER_EXPANDED_ROOT))
	test "$$(sha256sum $(PAPERCLIP_JAR) | cut -d' ' -f1)" = "$(PAPERCLIP_SHA256)"
	cd $(dir $(PAPER_EXPANDED_ROOT)) && \
		$(BOOT_JDK)/bin/java -Dpaperclip.patchonly=true \
		-DbundlerRepoDir=$(PAPER_EXPANDED_ROOT) -jar $(PAPERCLIP_JAR) nogui
	touch $@

$(STAGE)/paper/classpath.list: $(PAPER_EXPANDED_ROOT)/.stamp tools/stage_paper_payload.py
	$(PYTHON) tools/stage_paper_payload.py \
		--paperclip $(PAPERCLIP_JAR) \
		--cache-root $(PAPER_EXPANDED_ROOT) \
		--stage-root $(STAGE)/paper \
		--paperclip-sha256 $(PAPERCLIP_SHA256) \
		--server-sha256 $(PAPER_SERVER_SHA256) \
		--library-count $(PAPER_LIBRARY_COUNT)

PROFILE_STAGE_DEPS := $(STAGE)/paper/classpath.list
else
PROFILE_STAGE_DEPS :=
endif

JVM_OBJS := $(wildcard $(JDK_IMAGE)/../hotspot/variant-server/libjvm/objs/*.o)

JDK_NATIVE_SOURCES := $(shell find ../src/java.base -path '*/native/*' -type f) \
	$(shell find ../src/hotspot -type f)

$(BUILD)/jdk-native.stamp: $(JDK_NATIVE_SOURCES)
	$(MAKE) -C .. CONF=orbis-x86_64-server-fastdebug java.base-libs hotspot
	touch $@

$(BUILD)/jvm-link.x: $(TOOLCHAIN)/link.x tools/prepare_jvm_link.py
	mkdir -p $(BUILD)
	$(PYTHON) tools/prepare_jvm_link.py $< $@

NATIVE_INPUTS := $(wildcard $(JDK_SUPPORT)/native/java.base/lib*/*.o) \
                 $(wildcard $(JDK_SUPPORT)/native/java.base/lib*/*_link.cmdline) \
                 $(wildcard $(JDK_SUPPORT)/native/java.base/lib*/*objectfilenames.txt)

$(BUILD)/native.stamp: $(BUILD)/jdk-native.stamp $(JVM_OBJS) $(NATIVE_INPUTS) $(BUILD)/jvm-link.x \
        tools/build_native_modules.py tools/native_dlfcn.c tools/orbis_tls.c \
		tools/orbis_initializers.c tools/orbis_runtime.c tools/orbis_runtime.h \
		tools/test_orbis_runtime_source.py tools/test_orbis_runtime_binary.py \
		tools/test_prx_initializers.py \
		tools/audit_orbis_binaries.py tools/test_audit_orbis_binaries.py \
		tools/testdata/audit_binaries/manifest.json \
		tools/test_native_dependency_order.py \
		tools/test_orbis_file_length.py \
		tools/test_orbis_file_channel_size.py \
		tools/test_orbis_stat_abi.py \
		tools/test_random_access_diagnostics.py \
		tools/test_nio_filesystem_diagnostics.py \
		tools/test_orbis_nio_access.py \
		tools/test_orbis_signal_abi.py ../src/hotspot/os/orbis/signal.h Makefile \
		tools/test_orbis_signal_diagnostic.py \
		tools/test_orbis_condvar_clock.py \
		tools/test_orbis_clock_abi.py \
		tools/test_orbis_timed_parking.py \
		../src/hotspot/os/posix/signals_posix.cpp \
		tools/test_orbis_signal_diagnostic_binary.py \
		tools/test_orbis_pipe_fallback.py \
		tools/test_orbis_nio_tcp_nodelay.py \
		tools/test_orbis_nio_nonblocking.py \
		tools/testdata/orbis_nonblocking_test.c \
		../src/java.base/unix/native/libnio/ch/orbis_nonblocking.h \
		../src/java.base/unix/native/libnio/fs/orbis_errno.h \
		../src/java.base/unix/native/libnio/ch/IOUtil.c \
		../src/java.base/unix/native/libnio/ch/Net.c \
		tools/test_orbis_missing_errno.py \
		tools/testdata/orbis_missing_errno_test.c \
		tools/test_orbis_delete_errno_source.py \
		tools/test_orbis_lstat_source.py \
		tools/orbis_native_policy.json tools/audit_orbis_sources.py \
		tools/test_audit_orbis_sources.py \
		tools/testdata/audit_sources/direct.c \
		tools/testdata/audit_sources/prohibited.c \
		tools/test_jdk_native_rebuild.py
	$(PYTHON) tools/test_audit_orbis_sources.py
	$(PYTHON) tools/audit_orbis_sources.py \
		--policy tools/orbis_native_policy.json --root .. \
		--output $(BUILD)/orbis-source-audit.json
	$(PYTHON) tools/test_random_access_diagnostics.py
	$(PYTHON) tools/test_nio_filesystem_diagnostics.py
	$(PYTHON) tools/test_orbis_nio_access.py
	$(PYTHON) tools/test_orbis_file_length.py
	$(PYTHON) tools/test_orbis_file_channel_size.py
	$(PYTHON) tools/test_orbis_stat_abi.py --cc $(CC) \
		--toolchain $(TOOLCHAIN) --jdk ..
	$(PYTHON) tools/test_native_dependency_order.py
	$(PYTHON) tools/test_orbis_signal_abi.py --cc $(CC) \
		--toolchain $(TOOLCHAIN) --jdk ..
	$(PYTHON) tools/test_orbis_signal_diagnostic.py
	$(PYTHON) tools/test_orbis_condvar_clock.py
	$(PYTHON) tools/test_orbis_clock_abi.py --toolchain $(TOOLCHAIN) --sdk $(SDK)
	$(PYTHON) tools/test_orbis_timed_parking.py
	$(PYTHON) tools/test_orbis_pipe_fallback.py
	$(PYTHON) tools/test_orbis_nio_tcp_nodelay.py
	$(PYTHON) tools/test_orbis_nio_nonblocking.py
	$(PYTHON) tools/test_orbis_missing_errno.py
	$(PYTHON) tools/test_orbis_delete_errno_source.py
	$(PYTHON) tools/test_orbis_lstat_source.py
	$(PYTHON) tools/test_jdk_native_rebuild.py
	$(PYTHON) tools/build_native_modules.py --sdk $(SDK) --toolchain $(TOOLCHAIN) \
		--jdk $(JDK_IMAGE) --paid $(PAID) \
		--policy tools/orbis_native_policy.json
	$(PYTHON) tools/test_prx_initializers.py \
		$(BUILD)/native/libjvm.so $(BUILD)/native/libjava.so \
		$(BUILD)/native/libnet.so $(BUILD)/native/libnio.so \
		$(BUILD)/native/libzip.so $(BUILD)/native/libverify.so \
		$(BUILD)/native/libjimage.so
	$(PYTHON) tools/test_orbis_signal_diagnostic_binary.py
	touch $@


$(BUILD)/runtime-jmods.stamp: $(JDK_IMAGE)/modules Makefile
	mkdir -p $(BUILD)/jmods
	rm -f $(BUILD)/jmods/*.jmod
	module_list=$$(printf '%s' '$(RUNTIME_MODULES)' | tr ',' ' '); \
	for module in $$module_list; do \
		class_path="$(JDK_IMAGE)/modules/$$module"; \
		test -d "$$class_path" || { echo "Missing selected module: $$module"; exit 1; }; \
		$(JMOD) create --class-path "$$class_path" \
			--module-version $(RUNTIME_VERSION) \
			--target-platform orbis-x86_64 \
			$(BUILD)/jmods/$$module.jmod; \
	done
	touch $@

$(BUILD)/runtime-image.stamp: $(BUILD)/runtime-jmods.stamp
	rm -rf $(BUILD)/runtime-image
	$(JLINK) --module-path $(BUILD)/jmods \
		--add-modules $(RUNTIME_MODULES) \
		--output $(BUILD)/runtime-image \
		--strip-debug --no-header-files --no-man-pages --compress=2
	touch $@

$(BUILD)/java-image.stamp: $(BUILD)/runtime-image.stamp \
		$(BUILD)/native.stamp Makefile
	mkdir -p $(STAGE)/java/lib
	chmod -R u+w $(STAGE)/java
	cp -a $(JDK_IMAGE)/conf $(STAGE)/java/
	cp -a $(JDK_IMAGE)/lib/. $(STAGE)/java/lib/
	cp -a $(JDK_IMAGE)/release $(STAGE)/java/
	cp $(BUILD)/runtime-image/lib/modules $(STAGE)/java/lib/modules
	# The JVM is loaded as a native PRX; remove obsolete raw ELF bridge/core.
	rm -f $(STAGE)/libjvm.so $(STAGE)/jvm.so $(STAGE)/java/lib/server/libjvm.so
	if test -d $(JDK_SUPPORT)/modules_libs; then \
		mkdir -p $(STAGE)/support; \
		cp -a $(JDK_SUPPORT)/modules_libs $(STAGE)/support/; \
	fi
	find $(STAGE) -type l -print | while IFS= read -r link; do \
		target=$$(readlink -f "$$link"); \
		test -f "$$target" || { echo "Unresolved JDK link: $$link"; exit 1; }; \
		rm "$$link"; \
		cp -p "$$target" "$$link"; \
	done
	rm -rf $(STAGE)/support
	# Ship only converted native libraries for the startup dependency chain.
	find $(STAGE)/java/lib -name '*.so' -delete
	mkdir -p $(STAGE)/sce_module
	cp $(BUILD)/native/libjvm.prx $(STAGE)/sce_module/libjvm.prx
	for lib in java net nio zip verify jimage; do \
		cp $(BUILD)/native/lib$$lib.prx $(STAGE)/java/lib/lib$$lib.so; \
	done
	$(PYTHON) tools/check_native_package.py $(STAGE)
	touch $@

$(STAGE)/sce_sys/param.sfo: Makefile
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
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ ATTRIBUTE --type Integer --maxsize 4 --value 0
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ CATEGORY --type Utf8 --maxsize 4 --value gd
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ CONTENT_ID --type Utf8 --maxsize 48 --value '$(CONTENT_ID)'
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ DOWNLOAD_DATA_SIZE --type Integer --maxsize 4 --value 0
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ SYSTEM_VER --type Integer --maxsize 4 --value 0
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ TITLE --type Utf8 --maxsize 128 --value '$(TITLE)'
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ TITLE_ID --type Utf8 --maxsize 12 --value '$(TITLE_ID)'
	$(DOTNET) $(PKGTOOL) sfo_setentry $@ VERSION --type Utf8 --maxsize 8 --value '$(VERSION)'

$(STAGE)/package.gp4: $(STAGE)/eboot.bin $(STAGE)/app.jar $(PROFILE_STAGE_DEPS) \
		$(BUILD)/java-image.stamp $(STAGE)/sce_sys/param.sfo \
		Makefile tools/make_gp4.py tools/test_launcher_modes.py \
		tools/test_launcher_logging.py tools/test_orbis_probe_source.py \
		tools/test_orbis_probe_logging.py tools/test_headless_module_probe_source.py \
		tools/test_zipfs_diagnostics.py \
		tools/test_probe_root_source.py tools/test_probe_app_jar_source.py \
		tools/test_probe_failure_propagation.py \
		tools/test_trace_redirect.py \
		tools/test_orbis_signal_diagnostic_binary.py \
		tools/test_release_consistency.py tools/test_runtime_modules.py \
		tools/test_runtime_image_modules.py tools/test_exhaustive_probe_source.py
	$(PYTHON) tools/test_launcher_modes.py
	$(PYTHON) tools/test_launcher_logging.py
	$(PYTHON) tools/test_orbis_probe_source.py
	$(PYTHON) tools/test_orbis_probe_logging.py
	$(PYTHON) tools/test_headless_module_probe_source.py
	$(PYTHON) tools/test_zipfs_diagnostics.py
	$(PYTHON) tools/test_probe_root_source.py
	$(PYTHON) tools/test_probe_app_jar_source.py
	$(PYTHON) tools/test_probe_failure_propagation.py
	$(PYTHON) tools/test_trace_redirect.py
	$(PYTHON) tools/test_orbis_signal_diagnostic_binary.py
	$(PYTHON) tools/test_release_consistency.py
	$(PYTHON) tools/test_runtime_modules.py
	JIMAGE=$(BOOT_JDK)/bin/jimage $(PYTHON) tools/test_runtime_image_modules.py
	$(PYTHON) tools/test_exhaustive_probe_source.py
	$(PYTHON) tools/make_gp4.py --root $(STAGE) \
		--out $@ --content-id $(CONTENT_ID)

package: $(STAGE)/package.gp4
	$(PYTHON) tools/check_native_package.py $(STAGE)
	mkdir -p $(OUT)
	$(DOTNET) $(PKGTOOL) pkg_build $< $(OUT)
	$(DOTNET) $(PKGTOOL) pkg_validate --verbose \
		$(OUT)/$(CONTENT_ID).pkg

clean:
	rm -rf $(BUILD)
