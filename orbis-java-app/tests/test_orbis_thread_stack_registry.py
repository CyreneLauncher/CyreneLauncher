#!/usr/bin/env python3
import argparse
import pathlib
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--cxx", default="c++")
args = parser.parse_args()

tools = pathlib.Path(__file__).resolve().parent
source = tools / "orbis_thread_stack_registry_test.cpp"

with tempfile.TemporaryDirectory() as directory:
    binary = pathlib.Path(directory) / "orbis_thread_stack_registry_test"
    subprocess.run(
        [args.cxx, "-std=c++11", "-Wall", "-Wextra", "-Werror",
         str(source), "-o", str(binary)],
        check=True,
    )
    subprocess.run([str(binary)], check=True)
