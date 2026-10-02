#!/usr/bin/env python3
"""Require the Orbis file-length path to avoid the incompatible stat ABI."""

from pathlib import Path


source = Path("../src/java.base/unix/native/libjava/io_util_md.c").read_text()
start = source.index("handleGetLength(FD fd)")
body = source[start:source.index("\n}", start) + 2]

required = ("#if defined(ORBIS)", "SEEK_CUR", "SEEK_END", "SEEK_SET")
missing = [token for token in required if token not in body]
if missing:
    raise SystemExit("Orbis file-length fallback missing: " + ", ".join(missing))

print("Orbis file-length fallback preserves the descriptor position")
