import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class RenderContract(unittest.TestCase):
    def test_frame_orchestration_and_failure_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = pathlib.Path(directory) / "render_frames"
            subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror",
                            "-ffunction-sections", "-Wl,--gc-sections",
                            "-I", str(ROOT / "src"),
                            "-I", str(ROOT / "build/sources/mesa/include"),
                            str(ROOT / "tests/render_frames.c"),
                            str(ROOT / "src/probe_render.c"), "-o", str(executable)], check=True)
            subprocess.run([str(executable)], check=True)

    def test_pixel_comparator_rejects_wrong_expected_color(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            harness = root / "pixels.c"
            harness.write_text(
                '#include "probe_render.h"\n'
                'int main(void) {\n'
                ' unsigned char red[4]={255,0,0,255};\n'
                ' unsigned char wrong[4]={0,255,0,255};\n'
                ' unsigned char near[4]={253,2,0,255};\n'
                ' return !(probe_pixel_matches(red,red,0) && '
                '!probe_pixel_matches(red,wrong,1) && '
                'probe_pixel_matches(near,red,2) && '
                '!probe_pixel_matches(near,red,1));\n}\n')
            exe = root / "pixels"
            subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror",
                            "-I", str(ROOT / "src"),
                            "-I", str(ROOT / "build/sources/mesa/include"),
                            str(harness), str(ROOT / "src/probe_render.c"),
                            "-ffunction-sections", "-Wl,--gc-sections", "-o", str(exe)], check=True)
            subprocess.run([str(exe)], check=True)

    def test_render_source_contract(self):
        source = (ROOT / "src/probe_render.c").read_text()
        main = (ROOT / "src/probe_main.c").read_text()
        for expected in (
            "#version 150 core", "glGenVertexArrays", "glGenBuffers", "glDrawElements",
            "glTexSubImage2D", "glGenFramebuffers", "glCheckFramebufferStatus",
            "glReadPixels", "glBlendFunc", "glEnable", "glDepthFunc",
            "glGetShaderInfoLog", "glGetProgramInfoLog", "glGetError",
            "glFinish", "eglSwapInterval", "clock_gettime", "probe_egl_present",
        ):
            with self.subTest(expected=expected):
                self.assertIn(expected, source)
        self.assertIn("probe_render_run(&ctx, 600)", main)
        self.assertLess(main.index("probe_render_run(&ctx, 600)"), main.index("probe_egl_close(&ctx)"))
        self.assertIn('probe_control_requested("PROBE_FAIL_SHADER"', source)
        self.assertIn('probe_control_requested("PROBE_FAIL_SURFACE"', (ROOT / "src/probe_egl.c").read_text())

    def test_asymmetric_texture_samples_and_updates(self):
        source = (ROOT / "src/probe_render.c").read_text()
        self.assertIn("texture_pixels", source)
        self.assertIn("GL_RGBA8, 2, 2", source)
        self.assertIn('"triangle red texel", 32, 24, red, 0', source)
        self.assertIn('"triangle green texel", 16, 24, green, 0', source)
        self.assertIn('"triangle blue texel", 22, 48, blue, 0', source)
        self.assertIn("glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2, 2", source)

    def test_blend_alpha_is_explicit_and_opaque(self):
        source = (ROOT / "src/probe_render.c").read_text()
        self.assertIn("glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA,",
                      source)
        self.assertIn("GL_ONE, GL_ONE_MINUS_SRC_ALPHA)", source)
        # Source alpha 128/255 over opaque red. Separate alpha factors
        # yield 128 + 255 * (1 - 128/255) = 255.
        self.assertEqual(128 + 255 - 128, 255)
        self.assertIn("static const unsigned char blended[] = {127,0,128,255}", source)

    def test_shader_failure_mode_requires_exact_one(self):
        source = (ROOT / "src/probe_render.c").read_text()
        self.assertIn('probe_control_requested("PROBE_FAIL_SHADER"', source)
        self.assertIn('strcmp(value, "1") == 0', (ROOT / "src/probe_control.c").read_text())

    def test_fbo_negative_success_is_logged(self):
        source = (ROOT / "src/probe_render.c").read_text()
        self.assertIn('probe_log("FBO_NEGATIVE", "attachment-free framebuffer correctly incomplete")', source)


if __name__ == "__main__":
    unittest.main()
