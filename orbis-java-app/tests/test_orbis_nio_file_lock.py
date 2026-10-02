#!/usr/bin/env python3
"""Require an Orbis fallback for unsupported whole-file POSIX record locks."""

from pathlib import Path


source = Path("../src/java.base/unix/native/libnio/ch/FileDispatcherImpl.c").read_text(
    encoding="utf-8"
)
start = source.index("Java_sun_nio_ch_FileDispatcherImpl_lock0")
end = source.index("Java_sun_nio_ch_FileDispatcherImpl_release0", start)
lock = source[start:end]
start = end
end = source.index("static void closeFileDescriptor", start)
release = source[start:end]

assert "#include <sys/file.h>" in source
assert "flock(fd, operation)" in lock
assert "pos == 0" in lock and "java_lang_Long_MAX_VALUE" in lock
assert "errno == EINVAL" in lock
assert "operation |= LOCK_NB" in lock
assert "errno == EAGAIN || errno == EACCES" in lock
assert "flock(fd, LOCK_UN)" in release
assert "errno == EINVAL" in release
assert "pos == 0" in release and "java_lang_Long_MAX_VALUE" in release
print("Orbis whole-file lock fallback contract passed")
