#!/usr/bin/env python3
"""Ensure changed JDK native sources are compiled before PRX relinking."""

from pathlib import Path


makefile = Path("Makefile").read_text()

required = (
    "$(BUILD)/jdk-native.stamp:",
    "$(shell find ../src/java.base -path '*/native/*' -type f)",
    "$(shell find ../src/hotspot -type f)",
    "$(MAKE) -C .. CONF=orbis-x86_64-server-fastdebug java.base-libs hotspot",
    "$(BUILD)/native.stamp: $(BUILD)/jdk-native.stamp",
)

missing = [entry for entry in required if entry not in makefile]
if missing:
    raise SystemExit("missing JDK native rebuild wiring: " + ", ".join(missing))

print("JDK native rebuild wiring: PASS")
