#!/usr/bin/env python3
"""Check that Orbis RandomAccessFile tracing is diagnostic-only and opt-in."""

from pathlib import Path


source = Path("../src/java.base/share/native/libjava/RandomAccessFile.c").read_text()

required = (
    "orbis-java-raf: length",
    "orbis-java-raf: seek",
    "orbis-java-raf: read",
    "ORBIS_NATIVE_IO_TRACE",
    "orbis_raf_trace_enabled",
)

missing = [marker for marker in required if marker not in source]
if missing:
    raise SystemExit("missing RandomAccessFile diagnostics: " + ", ".join(missing))

print("RandomAccessFile diagnostics: length, seek, read")

if "orbis_raf_trace_enabled()" not in source:
    raise SystemExit("RandomAccessFile diagnostics are not gated")
