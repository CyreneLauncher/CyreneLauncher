#!/usr/bin/env python3
"""Ensure Orbis NIO path-based syscalls honor the emulated working directory."""

from pathlib import Path


source = Path("../src/java.base/unix/native/libnio/fs/UnixNativeDispatcher.c").read_text()

checks = {
    "open0": "Java_sun_nio_fs_UnixNativeDispatcher_open0",
    "opendir0": "Java_sun_nio_fs_UnixNativeDispatcher_opendir0",
    "mkdir0": "Java_sun_nio_fs_UnixNativeDispatcher_mkdir0",
    "rename0": "Java_sun_nio_fs_UnixNativeDispatcher_rename0",
    "rmdir0": "Java_sun_nio_fs_UnixNativeDispatcher_rmdir0",
    "unlink0": "Java_sun_nio_fs_UnixNativeDispatcher_unlink0",
    "chmod0": "Java_sun_nio_fs_UnixNativeDispatcher_chmod0",
    "chown0": "Java_sun_nio_fs_UnixNativeDispatcher_chown0",
    "utimes0": "Java_sun_nio_fs_UnixNativeDispatcher_utimes0",
    "statvfs0": "Java_sun_nio_fs_UnixNativeDispatcher_statvfs0",
}

for name, symbol in checks.items():
    body = source.split(symbol, 1)[1].split("\nJNIEXPORT", 1)[0]
    if "orbis_nio_resolve" not in body:
        raise SystemExit(f"Orbis NIO {name} does not resolve relative paths")

print("Orbis NIO relative paths: path syscalls use emulated cwd")

rename = source.split("Java_sun_nio_fs_UnixNativeDispatcher_rename0", 1)[1].split(
    "\nJNIEXPORT", 1)[0]
if rename.count("orbis_nio_resolve") < 2:
    raise SystemExit("Orbis NIO rename does not resolve both relative paths")
