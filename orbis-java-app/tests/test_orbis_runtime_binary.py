#!/usr/bin/env python3
"""Ensure packaged native ELFs avoid the unsupported libc exit route."""

from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
binaries = ([Path(path) for path in sys.argv[1:]] if len(sys.argv) > 1 else
            [ROOT / "build-runner" / "launcher.elf"])
assert binaries and all(path.is_file() for path in binaries), "native binaries must be built first"
bad = []
allocator_checked = False
for path in binaries:
    relocations = subprocess.run(["readelf", "-rW", str(path)], check=True,
                                 text=True, stdout=subprocess.PIPE).stdout
    if " _exit " in relocations or " _exit +" in relocations:
        bad.append(path.name)
    expansion = subprocess.run(["objdump", "-d", str(path),
                                "--disassemble=__expand_heap"], check=True,
                               text=True, stdout=subprocess.PIPE).stdout
    if "<__expand_heap>:" in expansion:
        allocator_checked = True
        assert "mmap_step" not in expansion, path.name
        assert "heap_growth_step" not in expansion, path.name
        assert "0x4000" in expansion, path.name
        assert "0x100000" in expansion, path.name
        assert "mmap" in expansion, path.name
    # The SDK libc answers some calls (link, fstatat, readlink, ...) with a stub that only sets
    # errno 78 (ENOSYS); the console then fails at run time. None may be linked in.
    stubs, name, body = [], None, []
    for line in subprocess.run(["objdump", "-d", "--no-show-raw-insn", str(path)], check=True,
                               text=True, stdout=subprocess.PIPE).stdout.splitlines() + [""]:
        if line.endswith(">:") or not line:
            if name and len(body) <= 8 and any("$0x4e,(%rax)" in b.replace(" ", "") for b in body):
                stubs.append(name)
            name, body = (line.split("<", 1)[1][:-2] if line.endswith(">:") else None), []
        elif name and line.strip():
            body.append(line)
    assert not stubs, path.name + " links ENOSYS libc stubs: " + ", ".join(stubs)
assert not bad, "unsupported _exit@plt calls: " + ", ".join(bad)
assert allocator_checked, "the launcher must include the shared Orbis libc heap hook"
print("Orbis runtime binary tests passed")
