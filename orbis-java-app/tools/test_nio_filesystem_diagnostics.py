#!/usr/bin/env python3
"""Check that the temporary Orbis NIO mkdir boundary probe is present."""

from pathlib import Path


source = Path("../src/java.base/unix/native/libnio/fs/UnixNativeDispatcher.c").read_text()

required = (
    "orbis-java-nio: mkdir",
    "saved_errno",
    "sceKernelDebugOutText",
)

missing = [marker for marker in required if marker not in source]
if missing:
    raise SystemExit("missing NIO filesystem diagnostics: " + ", ".join(missing))

print("NIO filesystem diagnostics: mkdir return and errno")
