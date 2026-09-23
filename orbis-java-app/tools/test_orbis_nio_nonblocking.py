#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
fixture = Path(__file__).with_name("testdata") / "orbis_nonblocking_test.c"

with tempfile.TemporaryDirectory() as directory:
    executable = Path(directory) / "orbis_nonblocking_test"
    subprocess.run([
        os.environ.get("HOST_CC", "cc"),
        "-std=c99",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-I", root / "src/java.base/unix/native/libnio/ch",
        fixture,
        "-o", executable,
    ], check=True)
    subprocess.run([executable], check=True)

print("Orbis NIO nonblocking postcondition behavior: PASS")
