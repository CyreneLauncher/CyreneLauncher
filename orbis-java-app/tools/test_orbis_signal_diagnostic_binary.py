#!/usr/bin/env python3
"""Ensure the shipped JVM PRX contains the corrected signal diagnostic."""

from pathlib import Path


path = Path("build/native/libjvm.prx")
data = path.read_bytes()
assert b"handled=%d" in data, (
    f"{path} does not contain the corrected handled-state diagnostic"
)
print("Orbis signal diagnostic binary contract passed")
