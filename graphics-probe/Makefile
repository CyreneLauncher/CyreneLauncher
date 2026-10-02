PROJECT_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
SDK ?= /home/wonder/PS4Toolchain
PKGTOOL ?= /home/wonder/pkgtool-net8/PkgTool.Core.dll
DOTNET ?= dotnet
PYTHON ?= python3

.PHONY: package
package:
	bash $(PROJECT_DIR)/tools/build_probe.sh $(PROJECT_DIR)/build/target $(SDK)
	$(PYTHON) $(PROJECT_DIR)/tools/package_probe.py --sdk $(SDK) --pkgtool $(PKGTOOL) --dotnet $(DOTNET)
