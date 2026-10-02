import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class EGLSourceContract(unittest.TestCase):
    def test_desktop_context_request_and_scanout(self):
        source = (ROOT / "src/probe_egl.c").read_text()
        for required in (
            "eglGetDisplay(EGL_DEFAULT_DISPLAY)",
            "eglBindAPI(EGL_OPENGL_API)",
            "EGL_OPENGL_BIT",
            "EGL_CONTEXT_MAJOR_VERSION, 3",
            "EGL_CONTEXT_MINOR_VERSION, 2",
            "EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT",
            "(EGLNativeWindowType)1",
            "eglGetProcAddress",
            "GL_CONTEXT_PROFILE_MASK",
            "EGL_EXTENSIONS",
        ):
            with self.subTest(required=required):
                self.assertIn(required, source)

    def test_cleanup_and_file_fallback(self):
        egl = (ROOT / "src/probe_egl.c").read_text()
        log = (ROOT / "src/probe_log.c").read_text()
        self.assertIn("eglDestroyContext", egl)
        self.assertIn("eglDestroySurface", egl)
        self.assertIn("eglTerminate", egl)
        self.assertIn("sceKernelDebugOutText", log)
        self.assertIn("/data/orbis-jdk/graphics-probe/probe.log", log)

    def test_uart_receives_raw_preformatted_line(self):
        log = (ROOT / "src/probe_log.c").read_text()
        self.assertIn("sceKernelDebugOutText(0, line)", log)
        self.assertNotIn('sceKernelDebugOutText(0, "%s", line)', log)
        self.assertIn('"[%s] %s: %s\\n"', log)
        self.assertIn("write(file_fd, line, length)", log)

    def test_truncated_line_keeps_newline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            harness = root / "log_harness.c"
            harness.write_text('#include "probe_log.h"\n'
                               'int main(void) { char message[900]; '
                               'for (int i=0; i<899; ++i) message[i]=\'x\'; '
                               'message[899]=0; probe_log("percent%stage", message); return 0; }\n')
            executable = root / "log_harness"
            subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror",
                            "-I", str(ROOT / "src"), str(harness),
                            str(ROOT / "src/probe_log.c"), "-o", str(executable)], check=True)
            result = subprocess.run([str(executable)], capture_output=True, check=True)
            self.assertEqual(len(result.stderr), 511)
            self.assertTrue(result.stderr.endswith(b"\n"))
            self.assertIn(b"percent%stage", result.stderr)


if __name__ == "__main__":
    unittest.main()
