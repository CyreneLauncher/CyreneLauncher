"""Offline Forge/NeoForge profile and processor regression tests."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ.get('BOOT_JDK17', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin'

class ForgeNeoForge(unittest.TestCase):
    def test_profiles_and_processors(self):
        import sys
        sys.path.insert(0, str(ROOT / 'tools'))
        import launcher_deps
        jars = [*sorted((ROOT / 'build/java-jars/3.2.2').glob('*.jar')),
                ROOT / 'build/downloads/runner-jars/gson-2.8.9.jar', *launcher_deps.jars()]
        sources = sorted((ROOT / 'launcher/java').rglob('*.java'))
        tests = sorted((ROOT / 'tests/launcher/orbis/launcher').glob('*Forge*Test.java'))
        tests += sorted((ROOT / 'tests/launcher/orbis/launcher').glob('InstallerProcessorsTest.java'))
        tests += sorted((ROOT / 'tests/launcher/orbis/launcher').glob('LoaderStartupTest.java'))
        with tempfile.TemporaryDirectory() as tmp:
            cp = ':'.join(map(str, jars))
            subprocess.run([str(JDK / 'javac'), '--release', '17', '-Xlint:all', '-Werror', '-cp', cp,
                            '-sourcepath', str(ROOT / 'lwjgl/java'), '-implicit:class', '-d', tmp,
                            *map(str, sources + tests)], check=True)
            for test in tests:
                subprocess.run([str(JDK / 'java'), '-cp', tmp + ':' + cp,
                                'orbis.launcher.' + test.stem,
                                str(ROOT / 'tests/fixtures/loaders'), tmp], check=True)
