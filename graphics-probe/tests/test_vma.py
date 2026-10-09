"""The pinned VMA JNI library must be a real Orbis PRX with every Java native."""
import os
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JARS = Path(os.environ.get('ORBIS_LWJGL_JARS', '/tmp/lwjgl-audit'))


class VmaBuild(unittest.TestCase):
    def test_pinned_vma_native_contract(self):
        builder = ROOT / 'tools/build_vma.py'
        self.assertTrue(builder.is_file(), 'Orbis VMA builder is missing')
        if not (JARS / 'lwjgl-vma-3.4.3.jar').is_file():
            self.skipTest('official LWJGL 3.4.3 VMA JAR required')
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            subprocess.run([sys.executable, str(builder), '--jars', str(JARS), '--output', str(out)], check=True)
            audit = json.loads((out / 'vma-audit.json').read_text())
            self.assertEqual(audit['release'], '3.4.3')
            self.assertGreater(audit['required_jni'], 70)
            self.assertEqual(audit['module']['jni_natives'], audit['required_jni'])
            self.assertEqual(audit['module']['needed'], ['libkernel.so'])
            self.assertEqual((out / 'liblwjgl_vma.prx').read_bytes()[:4], b'O\x15=\x1d')
            self.assertFalse(any(name.startswith('vk') for name in audit['module']['import_symbols']))


if __name__ == '__main__':
    unittest.main()
