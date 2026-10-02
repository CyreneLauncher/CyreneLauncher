#!/usr/bin/env python3
"""Ensure the Orbis flag repair is compiled into product builds."""

from pathlib import Path


for relative in ("../src/hotspot/share/runtime/init.cpp", "../src/hotspot/share/opto/c2compiler.cpp"):
    source = Path(relative).read_text()
    marker = "#if defined(ORBIS)"
    if marker not in source:
        raise SystemExit(f"{relative}: missing product-safe Orbis compiler guard")
    if "#if defined(ORBIS) && !defined(PRODUCT)" in source:
        raise SystemExit(f"{relative}: Orbis compiler repair is still debug-only")

print("Orbis release compiler guard: PASS")
