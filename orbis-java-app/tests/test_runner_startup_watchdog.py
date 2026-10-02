#!/usr/bin/env python3
"""Contract test for the opt-in JNI startup thread-dump watchdog."""

from pathlib import Path

root = Path(__file__).resolve().parents[1]
launcher = (root / "launcher.c").read_text()
makefile = (root / "Makefile").read_text()
readme = (root / "tools/runner-files-README.txt").read_text()

for token in (
    "startup-dump-after-seconds.txt",
    "pthread_create",
    "SIGQUIT",
    "sigaction",
    "JNI_CreateJavaVM",
    "orbis-startup-vm.log",
    "-XX:+LogVMOutput",
    "-XX:-DisplayVMOutput",
):
    assert token in launcher, token

assert "tests/run_checks.py" in makefile
assert "startup-dump-after-seconds.txt" in readme
assert "orbis-startup-vm.log" in readme

print("Runner startup watchdog contract passed")
