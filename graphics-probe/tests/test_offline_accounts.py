"""Exercise offline ownership, persistence, and real authentication session dispatch."""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JDK = Path('/usr/lib/jvm/java-17-openjdk-amd64/bin')


class OfflineAccountsTests(unittest.TestCase):
    def test_premium_gate_and_offline_session(self):
        gson = ROOT / 'build/downloads/runner-jars/gson-2.8.9.jar'
        sources = ROOT / 'launcher/java/orbis/launcher'
        with tempfile.TemporaryDirectory() as output:
            subprocess.run([str(JDK / 'javac'), '--release', '17', '-Xlint:all', '-Werror',
                            '-cp', str(gson), '-d', output,
                            *[str(sources / name) for name in ('AccountStore.java', 'LauncherFiles.java',
                                                             'MicrosoftAuthClient.java', 'LaunchRequest.java')],
                            str(ROOT / 'tests/launcher/orbis/launcher/OfflineAccountsTest.java')], check=True)
            subprocess.run([str(JDK / 'java'), '-cp', output + ':' + str(gson),
                            'orbis.launcher.OfflineAccountsTest', str(Path(output) / 'accounts-test')], check=True)
