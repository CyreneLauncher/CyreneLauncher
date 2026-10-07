"""Runner upload, official API, fixture, and injected-cleanup contract for CompatibilityProbe."""
import hashlib
import json
import ctypes
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from host_diagnostics import GLFW_INPUT_UNAVAILABLE, GLFW_POLICY_ERRORS, unexpected_host_stderr

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path('/usr/lib/jvm/java-17-openjdk-amd64/bin')
JARS = Path('/tmp/lwjgl-audit')
MAIN = 'orbis.compat.CompatibilityProbe'


class CompatibilityContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='lwjgl-compat-')
        cls.work = Path(cls.temp.name)
        cls.out = cls.work / 'upload'
        subprocess.run([sys.executable, str(ROOT / 'tools/build_lwjgl_compat.py'),
                        '--jars', str(JARS), '--output', str(cls.out)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_upload_inventory_and_hashes(self):
        manifest = json.loads((self.out / 'compat-manifest.json').read_text())
        self.assertEqual(MAIN, manifest['main_class'])
        self.assertEqual(7, len(list((self.out / 'libraries').rglob('*.jar'))))
        self.assertEqual(MAIN, json.loads((self.out / 'runner/version.json').read_text())['mainClass'])
        for path, digest in manifest['files'].items():
            self.assertEqual(digest, hashlib.sha256((self.out / path).read_bytes()).hexdigest(), path)
        self.assertIn('runner/fixtures/DejaVuSans.ttf', manifest['files'])
        self.assertIn('runner/fixtures/pixels.png', manifest['files'])
        self.assertIn('runner/fixtures/tone.ogg', manifest['files'])
        self.assertIn('COMPAT stage=', (self.out / 'UPLOAD.txt').read_text())

    def test_runner_configuration_and_budget(self):
        class Config(ctypes.Structure):
            _fields_ = [(name, ctypes.c_char * 512) for name in
                        ('java_home', 'jar', 'working_directory', 'output_log', 'temporary_directory',
                         'desktop_bundle', 'graphics_bundle', 'version_metadata', 'libraries')] + [
                             ('debug', ctypes.c_int), ('display', ctypes.c_int), ('input_diagnostics', ctypes.c_int)]
        library = self.work / 'runner-config.so'
        subprocess.run(['cc', '-std=c11', '-shared', '-fPIC',
                        str(ROOT.parent / 'orbis-java-app/tools/runner_config.c'),
                        '-o', str(library)], check=True)
        api = ctypes.CDLL(str(library))
        config, error = Config(), ctypes.create_string_buffer(1024)
        self.assertEqual(0, api.runner_config_load(
            str(self.out / 'runner/runner.conf').encode(), ctypes.byref(config), error, 1024), error.value)
        self.assertEqual((2, 1), (config.display, config.debug))
        self.assertEqual(b'/data/orbis-jdk/graphics', config.graphics_bundle)
        options = (self.out / 'runner/jvm-options.txt').read_text().splitlines()
        self.assertEqual(['-Xms512m', '-Xmx1024m', '-Dorbis.graphics.arenaMib=512'],
                         [line for line in options if line and not line.startswith('#')])
        for line in options:
            if line and not line.startswith('#'):
                self.assertEqual(0, api.runner_option_reserved(line.encode()), line)

    def test_injected_stages_fail_and_release_in_reverse_order(self):
        native = self.work / 'native'
        native.mkdir(exist_ok=True)
        for module in ('lwjgl', 'lwjgl-stb', 'lwjgl-opengl'):
            with zipfile.ZipFile(JARS / f'{module}-3.2.2-natives-linux.jar') as jar:
                name = 'lib' + module.replace('-', '_') + '.so'
                (native / name).write_bytes(jar.read(name))
        optional = ROOT / 'build/lwjgl-optional/host'
        for name in ('libopenal.so', 'liblwjgl_tinyfd.so'):
            (native / name).write_bytes((optional / name).read_bytes())
        fakes = self.work / 'fakes'
        facade = self.work / 'facade'
        subprocess.run([sys.executable, str(ROOT / 'tools/build_lwjgl_facade.py'), '--jars', str(JARS),
                        '--output', str(facade)], check=True, stdout=subprocess.DEVNULL)
        subprocess.run([str(JAVA / 'javac'), '--release', '17', '-d', str(fakes),
                        str(ROOT / 'tests/facade/orbis/graphics/NativePlatform.java'),
                        str(ROOT / 'tests/facade/runner/gpu/NativeInput.java')], check=True)
        cp = ':'.join(str(p) for p in [fakes, facade / 'orbis-lwjgl-3.2.2-adapter.jar',
                                      self.out / 'runner/target.jar',
                                      *(JARS / f'{module}-3.2.2.jar' for module in
                                        ('lwjgl', 'lwjgl-opengl', 'lwjgl-stb', 'lwjgl-openal', 'lwjgl-tinyfd'))])
        for stage in ('native-load', 'stb-callback', 'alc', 'tinyfd-cancel', 'glfw-init', 'glfw-window', 'glfw-policy'):
            result = subprocess.run([str(JAVA / 'java'), '-Dorg.lwjgl.system.allocator=system',
                                     '-Dorg.lwjgl.librarypath=' + str(native),
                                     '-Dorg.lwjgl.opengl.libname=/nonexistent/libGL.so',
                                     '-Dorbis.compat.fail=' + stage, '-cp', cp, MAIN,
                                     str(self.out / 'runner/fixtures')],
                                    capture_output=True, text=True)
            lines = [line for line in result.stdout.splitlines() if line.startswith('COMPAT ')]
            self.assertNotEqual(0, result.returncode, stage + result.stdout + result.stderr)
            deliberate = (GLFW_POLICY_ERRORS,) if stage == 'glfw-policy' else ()
            self.assertEqual('', unexpected_host_stderr(result.stderr, expected_probe_failure=True,
                                                        expected_errors=deliberate),
                             stage + '\n' + result.stderr)
            self.assertTrue(any(f'stage={stage} result=ok' in line for line in lines),
                            stage + '\n' + result.stdout + result.stderr)
            if stage == 'stb-callback':
                callback = next(line for line in lines if 'stage=stb-callback result=ok' in line)
                self.assertIn('read=', callback)
                self.assertIn('skip=', callback)
                self.assertIn('eof=', callback)
            if stage == 'glfw-policy':
                self.assertEqual(2, sum('stage=glfw-expected-error result=ok' in line for line in lines))
                self.assertFalse(any('stage=glfw-error result=fail' in line for line in lines), lines)
            self.assertEqual('COMPAT stage=summary result=fail', lines[-1], lines)
            teardown = [line for line in lines if 'stage=teardown result=ok' in line]
            expected = ['native allocation']
            if stage in ('glfw-init', 'glfw-window', 'glfw-policy'):
                expected.insert(0, 'glfwSetErrorCallback(null)')
            if stage in ('glfw-init', 'glfw-window', 'glfw-policy'):
                expected.insert(0, 'glfwTerminate')
            if stage in ('glfw-window', 'glfw-policy'):
                expected.insert(0, 'glfwDestroyWindow')
            self.assertEqual([f'COMPAT stage=teardown result=ok {name}' for name in
                              expected + ['released all acquired resources']], teardown, stage)

        # A created window can coexist with a failed input bridge. The GLFW error
        # callback must fail the probe before the injected window-stage failure.
        original = (ROOT / 'tests/facade/runner/gpu/NativeInput.java').read_text()
        old = 'public static int start() { started = true; return 0; }'
        self.assertIn(old, original)
        source = self.work / 'failed-input-src/runner/gpu/NativeInput.java'
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(original.replace(old, 'public static int start() { started = true; return 17; }'))
        failed_fakes = self.work / 'failed-input-classes'
        subprocess.run([str(JAVA / 'javac'), '--release', '17', '-d', str(failed_fakes), str(source)], check=True)
        run = subprocess.run([str(JAVA / 'java'), '-Dorg.lwjgl.system.allocator=system',
                              '-Dorg.lwjgl.librarypath=' + str(native),
                              '-Dorg.lwjgl.opengl.libname=/nonexistent/libGL.so',
                              '-Dorbis.compat.fail=glfw-window', '-cp', str(failed_fakes) + ':' + cp,
                              MAIN, str(self.out / 'runner/fixtures')], capture_output=True, text=True)
        lines = [line for line in run.stdout.splitlines() if line.startswith('COMPAT ')]
        self.assertNotEqual(0, run.returncode)
        self.assertEqual('', unexpected_host_stderr(run.stderr, expected_probe_failure=True,
                                                    expected_errors=(GLFW_INPUT_UNAVAILABLE,)), run.stderr)
        self.assertTrue(any('stage=glfw-error result=fail' in line and 'input service unavailable' in line
                            for line in lines), lines)
        self.assertTrue(any('stage=run result=fail' in line and 'unexpected GLFW error callback' in line
                            for line in lines), lines)
        self.assertFalse(any('stage=glfw-window result=ok' in line for line in lines), lines)
        self.assertEqual(['COMPAT stage=teardown result=ok ' + name for name in
                          ('glfwDestroyWindow', 'glfwTerminate', 'glfwSetErrorCallback(null)',
                           'native allocation', 'released all acquired resources')],
                         [line for line in lines if 'stage=teardown result=ok' in line])
        self.assertEqual('COMPAT stage=summary result=fail', lines[-1], lines)


if __name__ == '__main__':
    unittest.main()
