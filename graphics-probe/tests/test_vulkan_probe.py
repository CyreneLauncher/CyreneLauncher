"""Build the actual acceptance app and validate every metadata artifact and SDL entry point."""
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import classfile


class VulkanProbe(unittest.TestCase):
    def test_probe_build_and_native_contract(self):
        jars = Path(os.environ.get('ORBIS_LWJGL_JARS', '/tmp/lwjgl-audit'))
        if not (jars / 'lwjgl-vma-3.4.3.jar').is_file():
            self.skipTest('pinned LWJGL 3.4.3 JARs required')
        facade = Path(os.environ.get('ORBIS_FACADE_BUILD', ROOT / 'build/lwjgl-facade-vulkan-3.4.3'))
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            subprocess.run([sys.executable, str(ROOT / 'tools/build_vulkan_probe.py'), '--jars', str(jars),
                            '--facade', str(facade), '--output', str(out)], check=True)
            metadata = json.loads((out / 'version.json').read_text())
            self.assertEqual(metadata['mainClass'], 'orbis.vulkan.VulkanProbe')
            self.assertEqual(len(metadata['libraries']), 11)
            for entry in metadata['libraries']:
                artifact = entry['downloads']['artifact']
                data = (out / 'libraries' / artifact['path']).read_bytes()
                self.assertEqual(len(data), artifact['size'])
                self.assertEqual(hashlib.sha1(data).hexdigest(), artifact['sha1'])
            with zipfile.ZipFile(out / 'vulkan-probe.jar') as archive:
                refs = classfile.references(archive.read('orbis/vulkan/VulkanProbe.class'))
            with zipfile.ZipFile(facade / 'orbis-lwjgl-3.4.3-adapter.jar') as archive:
                for kind, owner, name, descriptor in refs:
                    if kind == 'method' and owner.startswith('org/lwjgl/sdl/'):
                        cls = classfile.parse(archive.read(owner + '.class'))
                        self.assertIn((name, descriptor), {(m.name, m.descriptor) for m in cls.methods})
            config = (out / 'runner.conf.example').read_text()
            self.assertIn('working_directory=', config)
            self.assertIn('version_metadata=', config)
            self.assertIn('libraries=', config)


if __name__ == '__main__':
    unittest.main()
