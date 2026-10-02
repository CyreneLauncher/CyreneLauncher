#!/usr/bin/env python3
"""Compile and run the platform-independent Orbis arena allocator tests."""

import os
import argparse
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
source = ROOT / "tests" / "orbis_direct_memory_arena_test.cpp"
parser = argparse.ArgumentParser()
parser.add_argument("--cxx", default=os.environ.get("HOST_CXX", "c++"))
compiler = parser.parse_args().cxx
if shutil.which(shlex.split(compiler)[0]) is None:
    raise SystemExit(f"C++ compiler not found: {compiler}")

with tempfile.TemporaryDirectory(prefix="orbis-arena-test-") as temp:
    binary = Path(temp) / "orbis-direct-memory-arena-test"
    subprocess.run(
        shlex.split(compiler)
        + ["-std=c++11", "-Wall", "-Wextra", "-Werror", str(source), "-o", str(binary)],
        check=True,
        cwd=ROOT,
    )
    subprocess.run([str(binary)], check=True, cwd=ROOT)
