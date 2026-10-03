#!/usr/bin/env python3
"""Pinned STB PRX contract and official Linux-native fixture semantics.

Host execution tests the Java fixture and upstream library behavior only. The
Orbis ABI and dyncall trampoline remain device gates in Task 4.
"""
import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent
BUILD = ROOT / 'build/lwjgl'
JARS = Path('/tmp/lwjgl-audit')
FIXTURES = ROOT / 'lwjgl-compat/resources'
FONT = WORKSPACE / 'orbis-java-app/runtime-data/desktop/fonts/DejaVuSans.ttf'
SOURCE = ROOT / 'lwjgl-compat/src/orbis/compat/StbChecks.java'
JAVA_HOME = Path('/usr/lib/jvm/java-17-openjdk-amd64')
EXPECTED = {
    'lwjgl-3.2.2.jar': '8ad6294407e15780b43e84929c40e4c5e997972e',
    'lwjgl-stb-3.2.2.jar': '3b8e6ebc5851dd3d17e37e5cadce2eff2a429f0f',
}


class STBContract(unittest.TestCase):
    def test_build_and_inputs(self):
        for name in ('liblwjgl_stb.so', 'liblwjgl_stb.prx', 'liblwjgl_stb-natives.txt',
                     'lwjgl-audit.json'):
            self.assertTrue((BUILD / name).is_file(), name + ' missing; run build_lwjgl_natives.py')
        audit = json.loads((BUILD / 'lwjgl-audit.json').read_text())['modules']['liblwjgl_stb']
        self.assertEqual(205, audit['jni_natives'])
        self.assertEqual(['libkernel.so'], audit['needed'])
        self.assertIn('tls', audit)
        exports = (BUILD / 'liblwjgl_stb-natives.txt').read_text().splitlines()
        self.assertIn('Java_org_lwjgl_stb_LibSTB_setupMalloc', exports)
        self.assertEqual(205, len(exports))
        for filename, digest in EXPECTED.items():
            path = JARS / filename
            self.assertTrue(path.is_file(), str(path))
            self.assertEqual(digest, hashlib.sha1(path.read_bytes()).hexdigest())
        for path in [SOURCE, FONT, FIXTURES / 'pixels.png', FIXTURES / 'tone.ogg']:
            self.assertTrue(path.is_file(), str(path))

    def test_host_fixture_semantics(self):
        for name, digest in EXPECTED.items():
            path = JARS / name
            self.assertTrue(path.is_file(), str(path))
            self.assertEqual(digest, hashlib.sha1(path.read_bytes()).hexdigest())
        with tempfile.TemporaryDirectory(prefix='stb-contract-') as temp:
            work = Path(temp)
            native = work / 'native'
            native.mkdir()
            for module in ('lwjgl', 'lwjgl-stb'):
                archive = JARS / f'{module}-3.2.2-natives-linux.jar'
                self.assertTrue(archive.is_file(), str(archive))
                with zipfile.ZipFile(archive) as jar:
                    (native / f'lib{module.replace("-", "_")}.so').write_bytes(
                        jar.read(f'lib{module.replace("-", "_")}.so'))
            fixtures = work / 'fixtures'
            fixtures.mkdir()
            for name in ('pixels.png', 'tone.ogg'):
                shutil.copyfile(FIXTURES / name, fixtures / name)
            shutil.copyfile(FONT, fixtures / 'DejaVuSans.ttf')
            launcher = work / 'Main.java'
            launcher.write_text('import java.nio.file.Paths;\n'
                                'public class Main { public static void main(String[] args) throws Exception '
                                '{ orbis.compat.StbChecks.run(Paths.get(args[0])); '
                                'System.out.println("STB host fixtures passed"); } }\n')
            classes = work / 'classes'
            classes.mkdir()
            classpath = ':'.join(str(JARS / name) for name in EXPECTED)
            subprocess.run([str(JAVA_HOME / 'bin/javac'), '-cp', classpath,
                            '-d', str(classes), str(SOURCE), str(launcher)], check=True)
            result = subprocess.run([str(JAVA_HOME / 'bin/java'),
                                     '-Dorg.lwjgl.system.allocator=system',
                                     '-Dorg.lwjgl.librarypath=' + str(native),
                                     '-cp', str(classes) + ':' + classpath,
                                     'Main', str(fixtures)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn('STB host fixtures passed', result.stdout)


if __name__ == '__main__':
    unittest.main()
