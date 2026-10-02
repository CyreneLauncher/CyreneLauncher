#!/usr/bin/env python3
"""Keep Orbis ProcessBuilder initialization distinct from process support."""

from pathlib import Path


root = Path(__file__).resolve().parents[2]
java = (root / "src/java.base/unix/classes/java/lang/ProcessImpl.java").read_text(
    encoding="utf-8"
)
native = (root / "src/java.base/unix/native/libjava/ProcessImpl_md.c").read_text(
    encoding="utf-8"
)

platform = java.split("static Platform get() {", 1)[1].split("\n        }", 1)[0]
assert 'osName.equals("Orbis")' in platform
assert "return BSD;" in platform
assert '"Process creation is not supported on Orbis"' in native
print("Orbis ProcessBuilder initialization contract: PASS")
