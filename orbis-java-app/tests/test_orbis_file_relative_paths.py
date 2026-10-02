#!/usr/bin/env python3
"""Ensure java.io.File operations use Orbis' emulated working directory."""

from pathlib import Path


source = Path("../src/java.base/unix/native/libjava/UnixFileSystem_md.c").read_text()
resolver = Path("../src/java.base/unix/native/libjava/orbis_io.h").read_text()
required = {
    "createDirectory": "Java_java_io_UnixFileSystem_createDirectory",
    "checkAccess": "Java_java_io_UnixFileSystem_checkAccess",
    "delete0": "Java_java_io_UnixFileSystem_delete0",
    "list": "Java_java_io_UnixFileSystem_list",
    "rename0": "Java_java_io_UnixFileSystem_rename0",
    "getLastModifiedTime": "Java_java_io_UnixFileSystem_getLastModifiedTime",
    "getLength": "Java_java_io_UnixFileSystem_getLength",
    "setLastModifiedTime": "Java_java_io_UnixFileSystem_setLastModifiedTime",
}
for name, symbol in required.items():
    body = source.split(symbol, 1)[1].split("\nJNIEXPORT", 1)[0]
    if name == "checkAccess":
        if "orbis_stat_path" not in body:
            raise SystemExit("Orbis java.io checkAccess does not resolve relative paths")
    elif "orbis_resolve_path" not in body and "orbis_stat_path" not in body:
        raise SystemExit(f"Orbis java.io {name} does not resolve relative paths")

print("Orbis java.io relative paths: file operations use emulated cwd")

if '"user.dir"' not in resolver or "CallStaticObjectMethod" not in resolver:
    raise SystemExit("Orbis path resolver does not fall back to JVM user.dir")

io_source = Path("../src/java.base/unix/native/libjava/io_util_md.c").read_text()
file_open = io_source.split("fileOpen(JNIEnv", 1)[1].split(
    "fileDescriptorClose(", 1
)[0]
if "orbis_resolve_path" not in file_open:
    raise SystemExit("Orbis FileOutputStream/RandomAccessFile open lost cwd resolution")
if "orbis_trace_target_open" not in file_open:
    raise SystemExit("targeted relative-file open diagnostic is missing")
