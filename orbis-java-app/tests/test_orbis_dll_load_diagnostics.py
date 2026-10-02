#!/usr/bin/env python3
"""Ensure Orbis native module loads expose the return boundary."""

from pathlib import Path


source = Path("../src/hotspot/os/orbis/os_orbis.cpp").read_text()

before = 'sceKernelDebugOutText(0, "orbis-jvm: native module load entered\\n");'
assert before in source, "Orbis loader must log entry into the configured loader adapter"

result_log = 'orbis-jvm: native adapter load returned handle='
assert result_log in source, "Orbis loader must log the load return boundary"
assert source.index(result_log) > source.index("::dlopen(filename"), (
    "return diagnostic must follow the module load call"
)

jimage = Path("../src/java.base/share/native/libjimage/jimage.cpp").read_text()
assert "jlong size = 0;" in Path("../src/hotspot/share/classfile/classLoader.cpp").read_text(), (
    "jimage probe size must be initialized"
)
assert "location != 0 ? (long long)size : -1LL" in Path(
    "../src/hotspot/share/classfile/classLoader.cpp"
).read_text(), "jimage probe must not report undefined size for a miss"

print("Orbis native load diagnostics contract passed")
