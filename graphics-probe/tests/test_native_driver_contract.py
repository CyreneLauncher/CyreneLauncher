"""Reject translated or incomplete drivers before creating a platform PRX."""
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class NativeDriverContract(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('native_driver_contract', ROOT / 'tools/native_driver_contract.py')
        self.contract = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.contract)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'meson-info').mkdir()
        self.configure(['radeonsi'])

    def configure(self, drivers):
        (self.root / 'meson-info/intro-buildoptions.json').write_text(json.dumps([
            {'name': 'gallium-drivers', 'value': drivers},
            {'name': 'vulkan-drivers', 'value': ['amd']},
        ]))

    def compile(self, extra='', radv=True):
        source = self.root / 'drivers.c'
        source.write_text('int radeonsi_driver_descriptor;\nvoid vkGetInstanceProcAddr(void) {}\n'
                          + ('void radv_GetInstanceProcAddr(void) {}\n' if radv else '') + extra)
        elf = self.root / 'drivers.o'
        subprocess.run(['cc', '-c', str(source), '-o', str(elf)], check=True)
        return elf

    def test_native_drivers_pass(self):
        self.assertEqual(self.contract.verify(self.compile(), self.root),
                         {'opengl': 'radeonsi', 'vulkan': 'radv', 'zink_compiled': False})

    def test_zink_build_configuration_is_rejected(self):
        self.configure(['zink', 'radeonsi'])
        with self.assertRaisesRegex(ValueError, 'radeonsi only'):
            self.contract.verify(self.compile(), self.root)

    def test_zink_linked_despite_native_configuration_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Zink driver symbols'):
            self.contract.verify(self.compile('void zink_create_screen(void) {}\n'), self.root)

    def test_missing_radv_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'missing native AMD driver symbols'):
            self.contract.verify(self.compile(radv=False), self.root)


if __name__ == '__main__':
    unittest.main()
