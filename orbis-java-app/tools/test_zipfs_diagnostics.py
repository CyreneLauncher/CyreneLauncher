#!/usr/bin/env python3
"""Keep the ZIP filesystem probe's target-side diagnostic boundaries intact."""

from pathlib import Path


source = Path("src/demo/Main.java").read_text(encoding="utf-8")
required = (
    'log.check("probe.zipfs", () -> zipFsProbe(log));',
    'private static void zipFsProbe(Log log) throws IOException',
    '"before initial delete"',
    '"after initial delete existed="',
    '"before newFileSystem"',
    '"after newFileSystem"',
    '"before entry write"',
    '"after entry roundtrip"',
    '"after close"',
    '"before final delete"',
    '"after final delete existed="',
)

missing = [marker for marker in required if marker not in source]
assert not missing, "missing ZIP filesystem diagnostics: " + ", ".join(missing)
print("ZIP filesystem probe diagnostics contract: PASS")
