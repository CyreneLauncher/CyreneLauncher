import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class EGLLifecycle(unittest.TestCase):
    def test_acquisition_failures_and_idempotent_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = pathlib.Path(directory) / "egl_lifecycle"
            subprocess.run([
                "cc", "-std=c11", "-Wall", "-Wextra", "-Werror",
                "-I", str(ROOT / "src"),
                "-I", str(ROOT / "build/sources/mesa/include"),
                str(ROOT / "tests/egl_lifecycle.c"),
                str(ROOT / "src/probe_egl.c"), str(ROOT / "src/probe_control.c"),
                "-o", str(executable),
            ], check=True)
            subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    unittest.main()
