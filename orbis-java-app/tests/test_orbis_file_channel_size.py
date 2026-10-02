#!/usr/bin/env python3
"""Require FileChannel.size() to avoid Orbis' incompatible struct stat ABI."""

from pathlib import Path


source = Path("../src/java.base/unix/native/libnio/ch/FileDispatcherImpl.c").read_text(
    encoding="utf-8"
)
start = source.index("Java_sun_nio_ch_FileDispatcherImpl_size0")
end = source.index("Java_sun_nio_ch_FileDispatcherImpl_lock0", start)
body = source[start:end]

required = ("#if defined(ORBIS)", "SEEK_CUR", "SEEK_END", "SEEK_SET")
missing = [token for token in required if token not in body]
assert not missing, "Orbis FileChannel size fallback missing: " + ", ".join(missing)
assert body.index("#if defined(ORBIS)") < body.index("fstat64")
print("Orbis FileChannel size fallback preserves descriptor position")
