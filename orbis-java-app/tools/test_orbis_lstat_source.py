#!/usr/bin/env python3
"""Ensure Orbis NIO uses lstat for no-follow-link metadata requests."""

from pathlib import Path


source = Path("../src/java.base/unix/native/libnio/fs/UnixNativeDispatcher.c").read_text(
    encoding="utf-8"
)
start = source.index("Java_sun_nio_fs_UnixNativeDispatcher_lstat0")
end = source.index("Java_sun_nio_fs_UnixNativeDispatcher_fstat", start)
lstat = source[start:end]

assert "RESTARTABLE(orbis_stat_path(path, &buf), err);" in lstat
assert "copy_orbis_stat_attributes(env, &buf, attrs);" in lstat
assert "throwUnixException(env, orbis_normalize_missing_errno(errno));" in lstat
assert "throwUnixException(env, ENOTSUP);" not in lstat
print("Orbis lstat native dispatch source contract: PASS")
