"""Exercise the direct RADV Vulkan bridge without a GPU or Linux Vulkan loader."""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class VulkanBridge(unittest.TestCase):
    def test_dispatch_surface_and_presentation_queue(self):
        source = ROOT / 'lwjgl/platform/orbis_vulkan.c'
        self.assertTrue(source.is_file(), 'Orbis Vulkan bridge is missing')
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / 'vulkan-bridge'
            subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror',
                            '-I', str(ROOT / 'build/sources/mesa/include'),
                            str(ROOT / 'tests/vulkan_bridge.c'), str(source), '-o', str(executable)], check=True)
            subprocess.run([str(executable)], check=True)


if __name__ == '__main__':
    unittest.main()
