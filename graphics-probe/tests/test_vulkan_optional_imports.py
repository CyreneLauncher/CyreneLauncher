import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('audit_link', Path(__file__).resolve().parents[1] / 'tools/audit_link.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)

class OptionalImportsTest(unittest.TestCase):
    def check_import(self, name, binding):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / 'lib/libkernel.so'
            library.parent.mkdir()
            library.touch()
            row = ['1:', '0', '0', 'NOTYPE', binding, 'DEFAULT', 'UND', name]
            with patch.object(audit, 'target_headers'), patch.object(audit, 'dynamic_symbols', side_effect=lambda p: [] if p == library else [row]), patch.object(audit.subprocess, 'run', side_effect=[SimpleNamespace(stdout='/libexec/ld-elf.so.1'), SimpleNamespace(stdout='(NEEDED) [libkernel.so]')]):
                return audit.audit_elf(Path(directory) / 'probe', directory)

    def test_optional_hooks_require_weak_binding(self):
        for name in ('orbis_vulkan_draw_pointer', 'orbis_vulkan_scanout_state'):
            self.check_import(name, 'WEAK')
            with self.assertRaisesRegex(ValueError, 'SDK import unresolved'):
                self.check_import(name, 'GLOBAL')

    def test_other_weak_imports_remain_rejected(self):
        with self.assertRaisesRegex(ValueError, 'SDK import unresolved'):
            self.check_import('unknown_hook', 'WEAK')
