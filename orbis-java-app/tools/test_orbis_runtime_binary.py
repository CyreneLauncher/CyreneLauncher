#!/usr/bin/env python3
"""Ensure packaged native ELFs avoid the unsupported libc exit route."""

from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]
binaries = [ROOT / "build" / "launcher.elf", *sorted((ROOT / "build" / "native").glob("lib*.so"))]
assert binaries and all(path.is_file() for path in binaries), "native binaries must be built first"
bad = []
for path in binaries:
    relocations = subprocess.run(["readelf", "-rW", str(path)], check=True,
                                 text=True, stdout=subprocess.PIPE).stdout
    if " _exit " in relocations or " _exit +" in relocations:
        bad.append(path.name)
    expansion = subprocess.run(["objdump", "-d", str(path),
                                "--disassemble=__expand_heap"], check=True,
                               text=True, stdout=subprocess.PIPE).stdout
    assert "<__expand_heap>:" in expansion, path.name
    assert "mmap_step" not in expansion, path.name
    assert "0x100000" in expansion, path.name
assert not bad, "unsupported _exit@plt calls: " + ", ".join(bad)
print("Orbis runtime binary tests passed")
