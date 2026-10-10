"""Host regression checks for interrupted transfers and redacted log sharing."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ.get('BOOT_JDK17', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin'


class DownloadAndLogSharing(unittest.TestCase):
    def test_recovery_and_reviewed_upload(self):
        gson = ROOT / 'build/downloads/runner-jars/gson-2.8.9.jar'
        java = ROOT / 'launcher/java/orbis/launcher'
        tests = ROOT / 'tests/launcher/orbis/launcher'
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run([str(JDK / 'javac'), '--release', '17', '-Xlint:all', '-Werror',
                            '-cp', str(gson), '-d', directory,
                            *[str(java / name) for name in ('DownloadCache.java', 'LogSharing.java', 'LauncherFiles.java')],
                            str(tests / 'DownloadRecoveryTest.java'), str(tests / 'LogSharingTest.java')], check=True)
            for name in ('DownloadRecoveryTest', 'LogSharingTest'):
                subprocess.run([str(JDK / 'java'), '-cp', directory + ':' + str(gson),
                                'orbis.launcher.' + name, str(Path(directory) / name)], check=True)
