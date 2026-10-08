#!/usr/bin/env python3
"""Contract tests for the runner's side of the one-shot HotSpot mapping trace.

The JDK port writes the trace (its os_orbis.cpp and native_dlfcn.c); the runner arms it and names
the dedicated log, which this checks along with the application's own copy of the loader adapter.
"""

from pathlib import Path

root = Path(__file__).resolve().parents[1]
launcher = (root / "launcher.c").read_text()
native = (root / "tools/native_dlfcn.c").read_text()
readme = (root / "README.md").read_text()

assert '"/data/orbis-jdk/logs/orbis-vmmap.log"' in native
assert 'strncmp(text, "orbis-vmmap ",' in native
assert '"/data/orbis-jdk/logs/orbis-vmmap.log"' in launcher
assert '"one-shot HotSpot mapping trace armed"' in launcher
assert 'setenv("ORBIS_VM_MAP_TRACE"' not in launcher
assert "orbis-vmmap.log" in readme

print("HotSpot mapping trace trigger and dedicated log contract passed")
