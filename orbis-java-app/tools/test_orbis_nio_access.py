#!/usr/bin/env python3
"""Check the Orbis access fallback used by Files.createDirectories."""

from pathlib import Path


source = Path("../src/java.base/unix/native/libnio/fs/UnixNativeDispatcher.c").read_text()
access = source.split(
    "Java_sun_nio_fs_UnixNativeDispatcher_access0", 1
)[1].split(
    "Java_sun_nio_fs_UnixNativeDispatcher_exists0", 1
)[0]
exists = source.split(
    "Java_sun_nio_fs_UnixNativeDispatcher_exists0", 1
)[1].split(
    "Java_sun_nio_fs_UnixNativeDispatcher_statvfs0", 1
)[0]

if "orbis_stat_path(path, &buf)" not in access or "orbis_access_allowed" not in access:
    raise SystemExit("Orbis access0 must use stat and mode checks")
if "throwUnixException(env, orbis_normalize_missing_errno(errno));" not in access:
    raise SystemExit("Orbis access0 must normalize absent-path errno")
if "throwUnixException(env, ENOTSUP)" in access:
    raise SystemExit("Orbis access0 must not reject every path as unsupported")
if "orbis_stat_path(path, &buf)" not in exists:
    raise SystemExit("Orbis exists0 must use stat")

print("Orbis NIO access fallback: stat existence and mode checks")
