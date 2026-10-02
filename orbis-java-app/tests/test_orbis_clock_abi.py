from pathlib import Path
import re
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("--toolchain", type=Path, default=Path("/home/wonder/PS4Toolchain"))
parser.add_argument("--sdk", type=Path, default=Path("/home/wonder/ps4-payload-sdk"))
args = parser.parse_args()

root = Path(__file__).resolve().parents[2]
source = (root / "src/hotspot/os/posix/os_posix.cpp").read_text()
toolchain_time = (args.toolchain / "include/time.h").read_text()
orbis_time = (args.sdk / "target/include/sys/time.h").read_text()

def clock_id(header):
    match = re.search(r"^#define\s+CLOCK_MONOTONIC\s+(\d+)\s*$", header, re.M)
    assert match is not None
    return int(match.group(1))

assert clock_id(toolchain_time) == 1
assert clock_id(orbis_time) == 4
assert "static const clockid_t hotspot_monotonic_clock = (clockid_t)4;" in source
assert "static const clockid_t hotspot_monotonic_clock = CLOCK_MONOTONIC;" in source
assert "clock_gettime(hotspot_monotonic_clock, &tp)" in source
assert "clockid_t clock = hotspot_monotonic_clock;" in source
assert "_pthread_condattr_setclock(_condAttr, hotspot_monotonic_clock)" in source
print("Orbis monotonic clock ABI contract passed")
