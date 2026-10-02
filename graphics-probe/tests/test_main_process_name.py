import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class MainProcessName(unittest.TestCase):
    def test_default_before_egl_and_preserves_override(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            main_object = root / "probe_main.o"
            executable = root / "main_process_name"
            includes = ["-I", str(ROOT / "src"), "-I", str(ROOT / "build/sources/mesa/include")]
            flags = ["-std=c11", "-D_POSIX_C_SOURCE=200809L", "-Wall", "-Wextra", "-Werror"]
            subprocess.run(["cc", *flags, *includes, "-Dmain=probe_app_main", "-c",
                            str(ROOT / "src/probe_main.c"), "-o", str(main_object)], check=True)
            subprocess.run(["cc", *flags, *includes,
                            str(ROOT / "tests/main_process_name.c"), str(main_object),
                            "-o", str(executable)], check=True)
            subprocess.run([str(executable), "default"], check=True)
            subprocess.run([str(executable), "custom-probe-name"], check=True)


if __name__ == "__main__":
    unittest.main()
