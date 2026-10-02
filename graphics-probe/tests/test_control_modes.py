import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class ControlModes(unittest.TestCase):
    def test_exact_one_or_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = pathlib.Path(directory) / "control_modes"
            subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror",
                            "-I", str(ROOT / "src"),
                            str(ROOT / "tests/control_modes.c"),
                            str(ROOT / "src/probe_control.c"), "-o", str(executable)], check=True)
            subprocess.run([str(executable)], check=True)

    def test_device_marker_paths_are_wired(self):
        egl = (ROOT / "src/probe_egl.c").read_text()
        render = (ROOT / "src/probe_render.c").read_text()
        self.assertIn('probe_control_requested("PROBE_FAIL_SURFACE",\n', egl)
        self.assertIn('"/data/orbis-jdk/graphics-probe/fail-surface"', egl)
        self.assertIn('probe_control_requested("PROBE_FAIL_SHADER",\n', render)
        self.assertIn('"/data/orbis-jdk/graphics-probe/fail-shader"', render)


if __name__ == "__main__":
    unittest.main()
