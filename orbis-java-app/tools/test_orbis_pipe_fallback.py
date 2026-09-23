#!/usr/bin/env python3
"""Ensure Orbis selector wakeups use the socketpair fallback."""

from pathlib import Path


source = Path("../src/java.base/unix/native/libnio/ch/IOUtil.c").read_text()
assert "#if defined(ORBIS)" in source
assert "socketpair(AF_UNIX, SOCK_STREAM, 0, fd)" in source
assert "pipe(fd)" in source
print("Orbis selector pipe fallback contract passed")
