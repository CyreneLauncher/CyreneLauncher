#!/usr/bin/env python3
"""Test launcher profile header generation and source integration."""

from pathlib import Path
import subprocess
import tempfile


root = Path(__file__).resolve().parents[1]
writer = root / "tools/write_launcher_profile.py"

with tempfile.TemporaryDirectory() as directory:
    output = Path(directory) / "launcher_profile.h"
    subprocess.run([
        "python3", str(writer), "--output", str(output),
        "--main-class", "runner/Main",
        "--user-dir", "/data/orbis-jdk/runner",
        "--log-path", "/data/orbis-jdk/logs/runner.log",
        "--tmp-dir", "/data/orbis-jdk/runner/tmp",
        "--runtime-jvm-options", "/data/orbis-jdk/runner/jvm-options.txt",
        "--screen-console",
        "--java-option=-Djava.awt.headless=true",
        "--java-option=-Dapplication.name=sample",
    ], check=True)
    text = output.read_text()
    for token in (
        'ORBIS_PROFILE_MAIN_CLASS "runner/Main"',
        'ORBIS_PROFILE_USER_DIR "/data/orbis-jdk/runner"',
        'ORBIS_PROFILE_LOG_PATH "/data/orbis-jdk/logs/runner.log"',
        'ORBIS_PROFILE_TMP_DIR "/data/orbis-jdk/runner/tmp"',
        '"-Djava.awt.headless=true"',
        '"-Dapplication.name=sample"',
        "ORBIS_PROFILE_VM_OPTION_COUNT",
        'ORBIS_PROFILE_RUNTIME_JVM_OPTIONS "/data/orbis-jdk/runner/jvm-options.txt"',
        "ORBIS_PROFILE_SCREEN_CONSOLE 1",
    ):
        assert token in text, token

source = (root / "launcher.c").read_text()
for token in (
    '#include "launcher_profile.h"', "ORBIS_PROFILE_MAIN_CLASS",
    "runner_config.working_directory", "ORBIS_PROFILE_LOG_PATH",
    "orbis_profile_vm_options", "ORBIS_PROFILE_VM_OPTION_COUNT",
    "prepare_profile_directories",
):
    assert token in source, token

makefile = (root / "Makefile").read_text()
for token in ("write_launcher_profile.py", "$(BUILD)/launcher_profile.h"):
    assert token in makefile, token

print("Launcher profile contract passed")
