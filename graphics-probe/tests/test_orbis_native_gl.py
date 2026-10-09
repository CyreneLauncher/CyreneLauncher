"""Run the patched EGL initializer: native OpenGL must never select Zink."""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MESA = ROOT / 'build/sources/mesa'
RELATIVE = 'src/egl/drivers/dri2/platform_orbis.c'


class NativeOpenGL(unittest.TestCase):
    def test_default_and_stale_override_use_native_radeonsi(self):
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            source = tmp / RELATIVE
            source.parent.mkdir(parents=True)
            source.write_bytes(subprocess.check_output(
                ['git', '-C', str(MESA), 'show', 'HEAD:' + RELATIVE]))
            for patch in sorted((ROOT / 'patches/mesa').glob('*.patch')):
                subprocess.run(['git', 'apply', '--include=' + RELATIVE, str(patch)],
                               cwd=tmp, check=True, capture_output=True)
            text = source.read_text()
            start = text.index('EGLBoolean\ndri2_initialize_orbis(')
            end = text.index('\n}', start) + 2
            initializer = text[start:end]
            # Before the fix the initializer calls this driver selector.
            selector = ''
            if 'orbis_gl_use_radeonsi()' in initializer:
                start = text.index('static bool\norbis_gl_use_radeonsi(')
                end = text.index('\n}', start) + 2
                selector = text[start:end]
            harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#define EGL_TRUE 1
#define EGL_FALSE 0
#define EGL_NOT_INITIALIZED 0x3001
#define _EGL_INFO 1
typedef int EGLBoolean;
typedef struct { int unused; } _EGLDisplay;
static struct dri2_egl_display {
    int fd_render_gpu, fd_display_gpu;
    char *driver_name;
    bool kopper;
    const void *loader_extensions, *vtbl;
} state;
static const void *orbis_image_loader_extensions[] = {NULL};
static const void *orbis_kopper_loader_extensions[] = {NULL};
static const int dri2_orbis_image_display_vtbl, dri2_orbis_display_vtbl;
static int screen_calls;
static struct dri2_egl_display *dri2_egl_display(_EGLDisplay *d) { return &state; }
static EGLBoolean _eglError(int code, const char *message) { return EGL_FALSE; }
static void _eglLog(int level, const char *message, ...) {}
static const char *os_get_option(const char *name) { return getenv(name); }
static void dri2_detect_swrast_kopper(_EGLDisplay *d) { state.kopper = true; }
static int dri2_create_screen(_EGLDisplay *d) { screen_calls++; return EGL_TRUE; }
static int dri2_setup_device(_EGLDisplay *d, int software) { return EGL_TRUE; }
static void dri2_setup_screen(_EGLDisplay *d) {}
static void dri2_orbis_add_configs(_EGLDisplay *d) {}
'''
            harness += selector + '\n' + initializer + r'''
int main(void) {
    const char *values[] = {NULL, "radeonsi", "zink"};
    _EGLDisplay display = {0};
    for (int i = 0; i < 3; i++) {
        if (values[i]) setenv("ORBIS_GL_DRIVER", values[i], 1);
        else unsetenv("ORBIS_GL_DRIVER");
        memset(&state, 0, sizeof(state));
        screen_calls = 0;
        assert(dri2_initialize_orbis(&display) == EGL_TRUE);
        assert(strcmp(state.driver_name, "radeonsi") == 0);
        assert(!state.kopper);
        assert(state.loader_extensions == orbis_image_loader_extensions);
        assert(state.vtbl == &dri2_orbis_image_display_vtbl);
        assert(screen_calls == 1);
        free(state.driver_name);
    }
    return 0;
}
'''
            c = tmp / 'native-gl.c'
            c.write_text(harness)
            executable = tmp / 'native-gl'
            subprocess.run(['cc', '-std=gnu11', '-o', str(executable), str(c)], check=True)
            result = subprocess.run([str(executable)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
