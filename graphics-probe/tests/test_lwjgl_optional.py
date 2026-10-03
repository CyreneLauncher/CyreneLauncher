"""Focused ABI and official LWJGL optional-provider contracts."""
import ctypes
import json
import subprocess
import tempfile
import zipfile
import shutil
import sys
import unittest
from unittest.mock import patch
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import orbis_prx
import tinyfd_contract
from make_lwjgl_metadata import SHA1
from host_diagnostics import unexpected_host_stderr

CORE = ('alcOpenDevice', 'alcCloseDevice', 'alcCreateContext', 'alcMakeContextCurrent',
        'alcProcessContext', 'alcSuspendContext', 'alcDestroyContext', 'alcGetCurrentContext',
        'alcGetContextsDevice', 'alcIsExtensionPresent', 'alcGetProcAddress', 'alcGetEnumValue',
        'alcGetError', 'alcGetString', 'alcGetIntegerv', 'alGetProcAddress')
BUILD = ROOT / 'build/lwjgl-optional'

class OptionalContract(unittest.TestCase):
    def test_host_diagnostics_reject_unexpected_warning(self):
        from host_diagnostics import LWJGL_CUSTOM_NATIVE, OPENAL_NO_DEVICE
        self.assertEqual('', unexpected_host_stderr(LWJGL_CUSTOM_NATIVE + OPENAL_NO_DEVICE))
        self.assertIn('unexpected warning', unexpected_host_stderr(
            LWJGL_CUSTOM_NATIVE + '[LWJGL] [WARN] unexpected warning\n'))

    def test_openal_abi(self):
        lib = ctypes.CDLL(str(BUILD / 'host/libopenal.so'))
        for name in CORE: self.assertTrue(getattr(lib, name))
        lib.alcOpenDevice.argtypes = [ctypes.c_char_p]; lib.alcOpenDevice.restype = ctypes.c_void_p
        lib.alcGetError.argtypes = [ctypes.c_void_p]; lib.alcGetError.restype = ctypes.c_int
        lib.alcIsExtensionPresent.argtypes = [ctypes.c_void_p, ctypes.c_char_p]; lib.alcIsExtensionPresent.restype = ctypes.c_int
        lib.alcGetString.argtypes = [ctypes.c_void_p, ctypes.c_int]; lib.alcGetString.restype = ctypes.c_void_p
        lib.alcGetIntegerv.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
        lib.alcGetProcAddress.argtypes = [ctypes.c_void_p, ctypes.c_char_p]; lib.alcGetProcAddress.restype = ctypes.c_void_p
        lib.alGetProcAddress.argtypes = [ctypes.c_char_p]; lib.alGetProcAddress.restype = ctypes.c_void_p
        lib.alcCreateContext.argtypes = [ctypes.c_void_p, ctypes.c_void_p]; lib.alcCreateContext.restype = ctypes.c_void_p
        lib.alcGetCurrentContext.restype = ctypes.c_void_p
        lib.alcMakeContextCurrent.argtypes = [ctypes.c_void_p]; lib.alcMakeContextCurrent.restype = ctypes.c_int
        lib.alcCloseDevice.argtypes = [ctypes.c_void_p]; lib.alcCloseDevice.restype = ctypes.c_int
        for _ in range(3):
            self.assertFalse(lib.alcIsExtensionPresent(None, b'ALC_ENUMERATE_ALL_EXT'))
            self.assertFalse(lib.alcGetString(None, 0x1005))
            values = (ctypes.c_int * 3)(7, 7, 7)
            lib.alcGetIntegerv(None, 0x1000, 3, values)
            self.assertEqual([0, 0, 0], list(values))
            self.assertFalse(lib.alcGetProcAddress(None, b'alcUnknown'))
            self.assertFalse(lib.alGetProcAddress(b'alUnknown'))
            self.assertTrue(lib.alcGetProcAddress(None, b'alcOpenDevice'))
            self.assertFalse(lib.alcOpenDevice(None))
            self.assertEqual(0xA001, lib.alcGetError(None))
            self.assertEqual(0, lib.alcGetError(None))
            self.assertFalse(lib.alcCreateContext(None, None))
            self.assertFalse(lib.alcMakeContextCurrent(None))
            self.assertFalse(lib.alcGetCurrentContext())
            self.assertTrue(lib.alcCloseDevice(None))

    def test_official_lwjgl_java(self):
        jars = Path('/tmp/lwjgl-audit')
        names = ('lwjgl-3.2.2.jar', 'lwjgl-openal-3.2.2.jar', 'lwjgl-tinyfd-3.2.2.jar')
        with tempfile.TemporaryDirectory(prefix='optional-lwjgl-') as directory:
            work = Path(directory); native = work / 'native'; native.mkdir()
            with zipfile.ZipFile(jars / 'lwjgl-3.2.2-natives-linux.jar') as jar:
                (native / 'liblwjgl.so').write_bytes(jar.read('liblwjgl.so'))
            shutil.copyfile(BUILD / 'host/libopenal.so', native / 'libopenal.so')
            shutil.copyfile(BUILD / 'host/liblwjgl_tinyfd.so', native / 'liblwjgl_tinyfd.so')
            classes = work / 'classes'; classes.mkdir()
            (work / 'Main.java').write_text('public class Main { public static void main(String[] args) '
                '{ orbis.compat.OptionalChecks.run(); System.out.println("optional LWJGL passed"); } }')
            cp = ':'.join(str(jars / name) for name in names)
            java = Path('/usr/lib/jvm/java-17-openjdk-amd64/bin')
            subprocess.run([str(java / 'javac'), '-cp', cp, '-d', str(classes),
                            str(ROOT / 'lwjgl-compat/src/orbis/compat/OptionalChecks.java'),
                            str(work / 'Main.java')], check=True)
            result = subprocess.run([str(java / 'java'), '-Dorg.lwjgl.openal.explicitInit=true',
                                     '-Dorg.lwjgl.librarypath=' + str(native), '-cp', str(classes) + ':' + cp,
                                     'Main'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn('optional LWJGL passed', result.stdout)
            self.assertEqual('', unexpected_host_stderr(result.stderr), result.stderr)

    def test_prx_audit_and_exports(self):
        audit = json.loads((BUILD / 'optional-audit.json').read_text())
        self.assertEqual('no-device', audit['behavior_mode'])
        for name in ('libopenal', 'liblwjgl_tinyfd'):
            module = audit['modules'][name]
            self.assertEqual(['libkernel.so'], module['needed'])
            self.assertIsNotNone(module['tls'])
        summary, _ = orbis_prx.audit(BUILD / 'libopenal.so', allowed_needed=('libkernel.so',),
                                     jni_prefix=None, jni_count=None, require_onload=False,
                                     required_c_exports=CORE)
        self.assertEqual(16, len(summary['c_exports']))
        self.assertEqual(sorted(CORE), summary['required_c_exports'])
        self.assertEqual(15, audit['modules']['liblwjgl_tinyfd']['jni_natives'])
        symbols = subprocess.check_output(['llvm-readelf', '--wide', '--dyn-syms', str(BUILD / 'liblwjgl_tinyfd.so')], text=True)
        expected = tinyfd_contract.expected_jni_exports(Path('/tmp/lwjgl-audit'))
        self.assertEqual(15, len(expected))
        exported = set((BUILD / 'liblwjgl_tinyfd-natives.txt').read_text().splitlines())
        self.assertEqual(expected, exported)
        self.assertEqual(expected, set(audit['modules']['liblwjgl_tinyfd']['jni_exports']))
        self.assertTrue(audit['modules']['libopenal']['import_symbols'])
        self.assertNotIn('popen', symbols); self.assertNotIn('system@', symbols)

    def test_source_checkout_contract_does_not_need_private_audit_json(self):
        self.assertEqual(15, len(tinyfd_contract.expected_jni_exports(Path('/tmp/lwjgl-audit'))))
        self.assertEqual(SHA1['lwjgl-tinyfd'],
                         'fcbe606c8f8da6f8f9a05e2c540eb1ee8632b0e9')
        self.assertNotIn('results/minecraft-1.18.2-native-declarations.json',
                         (ROOT / 'tools/build_lwjgl_optional.py').read_text())
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'lwjgl-tinyfd-3.2.2.jar'
            jar.write_bytes((Path('/tmp/lwjgl-audit') / jar.name).read_bytes() + b'changed')
            with self.assertRaisesRegex(ValueError, 'non-official pinned TinyFD JAR'):
                tinyfd_contract.expected_jni_exports(Path(directory))

    def test_actual_relevant_c_exports_are_separate_from_requirements(self):
        elf = BUILD / 'libopenal.so'
        original = orbis_prx.readelf
        def added(path, *args):
            result = original(path, *args)
            if args == ('--dyn-syms',):
                result += '\n  999: 0000000000000100 8 FUNC GLOBAL DEFAULT 1 alcExtraProviderEntry\n'
            return result
        with patch.object(orbis_prx, 'readelf', side_effect=added):
            summary, _ = orbis_prx.audit(elf, allowed_needed=('libkernel.so',),
                jni_prefix=None, jni_count=None, require_onload=False, required_c_exports=CORE)
        self.assertIn('alcExtraProviderEntry', summary['c_exports'])
        self.assertNotIn('alcExtraProviderEntry', summary['required_c_exports'])

    def test_audit_rejects_missing_c_export_and_dependency(self):
        elf = BUILD / 'libopenal.so'
        original = orbis_prx.readelf
        def absent(path, *args):
            result = original(path, *args)
            return result.replace('alcOpenDevice', 'absentOpenDev') if args == ('--dyn-syms',) else result
        with patch.object(orbis_prx, 'readelf', side_effect=absent):
            with self.assertRaisesRegex(SystemExit, 'alcOpenDevice'):
                orbis_prx.audit(elf, allowed_needed=('libkernel.so',), jni_prefix=None,
                                jni_count=None, require_onload=False, required_c_exports=CORE)
        def dependency(path, *args):
            result = original(path, *args)
            return result + ' 0x0000000000000001 (NEEDED) Shared library: [libunexpected.so]\n' if args == ('-d',) else result
        with patch.object(orbis_prx, 'readelf', side_effect=dependency):
            with self.assertRaisesRegex(SystemExit, 'unexpected dependencies'):
                orbis_prx.audit(elf, allowed_needed=('libkernel.so',), jni_prefix=None,
                                jni_count=None, require_onload=False, required_c_exports=CORE)

    def test_no_dialog_process_creation_imports(self):
        dynamic = subprocess.check_output(['llvm-readelf', '--wide', '--dyn-syms',
                                           str(BUILD / 'liblwjgl_tinyfd.so')], text=True)
        imports = [line.split()[-1].split('@')[0] for line in dynamic.splitlines()
                   if len(line.split()) >= 8 and line.split()[6] == 'UND']
        blocked = {'system', 'popen', 'fork', 'vfork', 'posix_spawn', 'posix_spawnp',
                   'execve', 'execl', 'execv', 'execvp', 'execvpe', 'execlp', 'execle'}
        self.assertFalse(blocked.intersection(imports), imports)

if __name__ == '__main__': unittest.main()
