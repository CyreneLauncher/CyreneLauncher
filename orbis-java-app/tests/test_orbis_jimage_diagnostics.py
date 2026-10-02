#!/usr/bin/env python3
"""Ensure Orbis jimage decompressor startup exposes its boundaries."""

from pathlib import Path


source = Path("../src/java.base/share/native/libjimage/imageDecompressor.cpp").read_text()
jimage = Path("../src/java.base/share/native/libjimage/jimage.cpp").read_text()
for marker in (
    "orbis-jimage: decompressor init entered",
    "orbis-jimage: ZIP_InflateFully resolved",
    "orbis-jimage: decompressor objects allocated",
    "orbis-jimage: decompressor init complete",
    "get resource entered",
    "get resource returned",
    "orbis-jimage: resource decompression entered",
    "orbis-jimage: resource decompression returned",
):
    assert marker in (source + jimage), f"missing diagnostic marker: {marker}"

print("Orbis jimage decompressor diagnostics contract passed")
