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
        "--main-class", "paperbootstrap/Main",
        "--user-dir", "/data/orbis-jdk/paper",
        "--log-path", "/data/orbis-jdk/logs/orbis-paper.log",
        "--tmp-dir", "/data/orbis-jdk/paper/tmp",
        "--java-option=-Djava.awt.headless=true",
        "--java-option=-Dorbis.paper.root=/app0/paper",
    ], check=True)
    text = output.read_text()
    for token in (
        'ORBIS_PROFILE_MAIN_CLASS "paperbootstrap/Main"',
        'ORBIS_PROFILE_USER_DIR "/data/orbis-jdk/paper"',
        'ORBIS_PROFILE_LOG_PATH "/data/orbis-jdk/logs/orbis-paper.log"',
        'ORBIS_PROFILE_TMP_DIR "/data/orbis-jdk/paper/tmp"',
        '"-Djava.awt.headless=true"',
        '"-Dorbis.paper.root=/app0/paper"',
        "ORBIS_PROFILE_VM_OPTION_COUNT",
    ):
        assert token in text, token

source = (root / "launcher.c").read_text()
for token in (
    '#include "launcher_profile.h"', "ORBIS_PROFILE_MAIN_CLASS",
    "ORBIS_PROFILE_USER_DIR", "ORBIS_PROFILE_LOG_PATH",
    "orbis_profile_vm_options", "ORBIS_PROFILE_VM_OPTION_COUNT",
    "prepare_profile_directories",
):
    assert token in source, token

makefile = (root / "Makefile").read_text()
for token in ("write_launcher_profile.py", "$(BUILD)/launcher_profile.h"):
    assert token in makefile, token

print("Launcher profile contract passed")
