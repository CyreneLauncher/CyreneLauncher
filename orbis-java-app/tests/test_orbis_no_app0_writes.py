#!/usr/bin/env python3
"""Guard writable Orbis runtime paths from regressing to package storage."""

from pathlib import Path

root = Path(__file__).resolve().parents[2]
makefile = (root / "orbis-java-app/Makefile").read_text()
compile_broker = (root / "src/hotspot/share/compiler/compileBroker.cpp").read_text()
c1 = (root / "src/hotspot/share/c1/c1_Compilation.cpp").read_text()
ostream = (root / "src/hotspot/share/utilities/ostream.cpp").read_text()
vm_error = (root / "src/hotspot/share/utilities/vmError.cpp").read_text()
os_orbis = (root / "src/hotspot/os/orbis/os_orbis.cpp").read_text()

assert "--user-dir /data/orbis-jdk/runner" in makefile
assert "#ifdef ORBIS" in compile_broker and "last_try_temp_dir" in compile_broker
assert 'fopen("c1_compile_only", "wt")' not in c1
assert 'fopen(".hotspot_compiler", "at")' not in c1
assert "first_directory = log_name[0] == '/' ? NULL : os::get_temp_directory()" in ostream
assert "package-backed /app0 current directory" in vm_error
assert "Do not permanently cache that early fallback" in os_orbis
assert "__atomic_compare_exchange_n(&current_path" in os_orbis
assert "static volatile int initialized" not in os_orbis

print("Orbis writable-path contract passed")
