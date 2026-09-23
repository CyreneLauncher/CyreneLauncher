#!/usr/bin/env python3
"""Contract tests for shared Orbis allocation and termination support."""

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
runtime = (ROOT / "tools" / "orbis_runtime.c").read_text()
header = (ROOT / "tools" / "orbis_runtime.h").read_text()
hotspot = (ROOT.parent / "src" / "hotspot" / "os" / "orbis" / "os_orbis.cpp").read_text()
debug = (ROOT.parent / "src" / "hotspot" / "share" / "utilities" / "debug.cpp").read_text()
os_runtime = (ROOT.parent / "src" / "hotspot" / "share" / "runtime" / "os.cpp").read_text()

assert "orbis_runtime_allocator_probe" in runtime
assert "struct orbis_alloc_result" in header
assert "sceLibcHeapSize" in runtime
assert "sceLibcHeapExtendedAlloc" in runtime
assert "orbis_runtime_exit" in runtime
assert "__expand_heap" in runtime
assert "ORBIS_HEAP_ARENA_SIZE" in runtime
assert "MAP_PRIVATE | MAP_ANON" in runtime
assert "__attribute__((noreturn))" in header
assert re.search(r"\b::_exit\s*\(", hotspot) is None
assert "orbis-jvm: native allocation failed" in debug
assert "requested=" in debug
assert "orbis-jvm: malloc failed" in os_runtime
assert "mmap_control=" in os_runtime
assert "MAP_ANON" in os_runtime
print("Orbis runtime source tests passed")
