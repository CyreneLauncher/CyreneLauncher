"""Exercise the direct RADV Vulkan bridge without a GPU or Linux Vulkan loader."""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class VulkanBridge(unittest.TestCase):
    def test_actual_no_api_window_lifecycle(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / 'vulkan-window'
            subprocess.run(['cc', '-std=gnu11', '-ffunction-sections', '-fdata-sections',
                            '-I', '/usr/lib/jvm/java-17-openjdk-amd64/include',
                            '-I', '/usr/lib/jvm/java-17-openjdk-amd64/include/linux',
                            '-I', str(ROOT / 'build/sources/mesa/include'),
                            '-idirafter', '/home/wonder/PS4Toolchain/include',
                            str(ROOT / 'tests/vulkan_window.c'), str(ROOT / 'lwjgl/platform/orbis_vulkan.c'),
                            '-Wl,--gc-sections', '-o', str(executable)], check=True)
            subprocess.run([str(executable)], check=True)

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
