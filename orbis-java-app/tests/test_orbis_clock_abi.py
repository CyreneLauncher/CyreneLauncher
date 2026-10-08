#!/usr/bin/env python3
"""The shared runtime's clock_gettime maps the toolchain's Linux clock ids to FreeBSD's."""
from pathlib import Path
import re
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("--toolchain", type=Path, default=Path("/home/wonder/PS4Toolchain"))
parser.add_argument("--sdk", type=Path, default=Path("/home/wonder/ps4-payload-sdk"))
args = parser.parse_args()

root = Path(__file__).resolve().parents[1]
toolchain_time = (args.toolchain / "include/time.h").read_text()
orbis_time = (args.sdk / "target/include/sys/time.h").read_text()

def clock_id(header):
    match = re.search(r"^#define\s+CLOCK_MONOTONIC\s+(\d+)\s*$", header, re.M)
    assert match is not None
    return int(match.group(1))

assert clock_id(toolchain_time) == 1
assert clock_id(orbis_time) == 4
# The eboot and every application PRX get the runtime's clock_gettime.
runtime = (root / "tools/orbis_runtime.c").read_text()
assert '__asm__("sceKernelClockGettime")' in runtime
assert "case 1: case 4: case 6: case 7: native = 4; break;" in runtime
assert "case 2: case 3: native = 2; break;" in runtime
print("Orbis monotonic clock ABI contract passed")
