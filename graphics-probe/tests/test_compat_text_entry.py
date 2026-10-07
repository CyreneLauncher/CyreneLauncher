#!/usr/bin/env python3
"""Compiles the compatibility mod's TextEntry (it needs only the JDK) with a BookEditScreen stand-in and
checks how accepted text is written into book pages and titles."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ.get('BOOT_JDK', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin'
with tempfile.TemporaryDirectory() as directory:
    subprocess.run([str(JAVA / 'javac'), '--release', '17', '-d', directory,
                    str(ROOT / 'launcher/compat/java/orbis/compat/TextEntry.java'),
                    str(ROOT / 'tests/compat/TextEntryBookTest.java')], check=True)
    subprocess.run([str(JAVA / 'java'), '-cp', directory, 'TextEntryBookTest'], check=True)
