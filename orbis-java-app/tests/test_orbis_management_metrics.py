#!/usr/bin/env python3
"""Contract checks for Orbis management metrics."""

from pathlib import Path

root = Path(__file__).resolve().parents[2]
unix = root / "src/jdk.management/bsd/native/libmanagement_ext/UnixOperatingSystem.c"
os_impl = root / "src/jdk.management/unix/native/libmanagement_ext/OperatingSystemImpl.c"

unix_source = unix.read_text()
os_source = os_impl.read_text()

assert "#if defined(ORBIS)" in unix_source
assert "JVM_ActiveProcessorCount()" in unix_source
assert "getHostOnlineCpuCount0" in unix_source
assert "#if defined(ORBIS)" in os_source
assert "sceKernelGetDirectMemorySize()" in os_source
assert "JVM_MaxMemory()" in os_source

print("Orbis management metrics contract passed")
