#!/usr/bin/env python3
"""Check that the temporary Orbis RandomAccessFile boundary probe is present."""

from pathlib import Path


source = Path("../src/java.base/share/native/libjava/RandomAccessFile.c").read_text()

required = (
    "orbis-java-raf: length",
    "orbis-java-raf: seek",
    "orbis-java-raf: read",
)

missing = [marker for marker in required if marker not in source]
if missing:
    raise SystemExit("missing RandomAccessFile diagnostics: " + ", ".join(missing))

print("RandomAccessFile diagnostics: length, seek, read")
