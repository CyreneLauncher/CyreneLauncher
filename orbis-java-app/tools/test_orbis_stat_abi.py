#!/usr/bin/env python3
"""Verify that java.nio decodes the native Orbis stat layout explicitly."""

import argparse
import subprocess
import tempfile
from pathlib import Path


def function_body(source: str, name: str, next_name: str) -> str:
    start = source.index(name)
    end = source.index(next_name, start)
    return source[start:end]


parser = argparse.ArgumentParser()
parser.add_argument("--cc", required=True)
parser.add_argument("--toolchain", required=True)
parser.add_argument("--jdk", required=True)
args = parser.parse_args()

jdk = Path(args.jdk).resolve()
header = jdk / "src/java.base/unix/native/libjava/orbis_io.h"
dispatcher = jdk / "src/java.base/unix/native/libnio/fs/UnixNativeDispatcher.c"

assert header.is_file(), "missing shared Orbis filesystem ABI header"

fixture = r'''
#define ORBIS 1
#include "orbis_io.h"

_Static_assert(sizeof(OrbisNativeStat) == 120, "Orbis stat size");
_Static_assert(offsetof(OrbisNativeStat, dev) == 0, "st_dev offset");
_Static_assert(offsetof(OrbisNativeStat, ino) == 4, "st_ino offset");
_Static_assert(offsetof(OrbisNativeStat, mode) == 8, "st_mode offset");
_Static_assert(offsetof(OrbisNativeStat, nlink) == 10, "st_nlink offset");
_Static_assert(offsetof(OrbisNativeStat, uid) == 12, "st_uid offset");
_Static_assert(offsetof(OrbisNativeStat, gid) == 16, "st_gid offset");
_Static_assert(offsetof(OrbisNativeStat, rdev) == 20, "st_rdev offset");
_Static_assert(offsetof(OrbisNativeStat, atim) == 24, "st_atim offset");
_Static_assert(offsetof(OrbisNativeStat, mtim) == 40, "st_mtim offset");
_Static_assert(offsetof(OrbisNativeStat, ctim) == 56, "st_ctim offset");
_Static_assert(offsetof(OrbisNativeStat, size) == 72, "st_size offset");
_Static_assert(offsetof(OrbisNativeStat, blocks) == 80, "st_blocks offset");
_Static_assert(offsetof(OrbisNativeStat, blksize) == 88, "st_blksize offset");
_Static_assert(offsetof(OrbisNativeStat, flags) == 92, "st_flags offset");
_Static_assert(offsetof(OrbisNativeStat, gen) == 96, "st_gen offset");
_Static_assert(offsetof(OrbisNativeStat, birthtim) == 104, "birthtime offset");

int exercise(const char *path, int fd, OrbisNativeStat *out) {
    int path_result = orbis_stat_path(path, out);
    int fd_result = orbis_fstat_fd(fd, out);
    return path_result | fd_result;
}
'''

with tempfile.TemporaryDirectory() as directory:
    source_path = Path(directory) / "stat_abi.c"
    source_path.write_text(fixture, encoding="utf-8")
    command = [
        args.cc,
        "--target=x86_64-pc-freebsd12-elf",
        "-std=c11",
        "-D_ALLBSD_SOURCE",
        "-isystem", str(Path(args.toolchain) / "include"),
        "-I", str(header.parent),
        "-fsyntax-only",
        str(source_path),
    ]
    subprocess.run(command, check=True)

source = dispatcher.read_text(encoding="utf-8")
stat0 = function_body(source, "UnixNativeDispatcher_stat0", "UnixNativeDispatcher_stat1")
stat1 = function_body(source, "UnixNativeDispatcher_stat1", "UnixNativeDispatcher_lstat0")
lstat0 = function_body(source, "UnixNativeDispatcher_lstat0", "UnixNativeDispatcher_fstat")
fstat = function_body(source, "UnixNativeDispatcher_fstat", "UnixNativeDispatcher_fstatat0")
access = function_body(source, "UnixNativeDispatcher_access0", "UnixNativeDispatcher_exists0")
exists = function_body(source, "UnixNativeDispatcher_exists0", "UnixNativeDispatcher_statvfs0")

assert "orbis_stat_path" in stat0, "stat0 still uses the compiler's stat layout"
assert "copy_orbis_stat_attributes" in stat0, "stat0 does not decode Orbis metadata"
assert "orbis_stat_path" in stat1, "stat1 still uses the compiler's stat layout"
assert "orbis_stat_path" in lstat0, "lstat0 still uses the compiler's stat layout"
assert "copy_orbis_stat_attributes" in lstat0, "lstat0 does not decode Orbis metadata"
assert "orbis_fstat_fd" in fstat, "fstat still uses the compiler's stat layout"
assert "copy_orbis_stat_attributes" in fstat, "fstat does not decode Orbis metadata"
assert "OrbisNativeStat" in access, "access checks use the compiler's stat layout"
assert "orbis_stat_path" in access, "access checks bypass the Orbis stat wrapper"
assert "OrbisNativeStat" in exists, "existence checks use the compiler's stat layout"
assert "orbis_stat_path" in exists, "existence checks bypass the Orbis stat wrapper"

print("Orbis stat layout and java.nio metadata routes are explicit")
