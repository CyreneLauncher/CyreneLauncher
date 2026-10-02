#include "probe_egl.h"
#include <GL/gl.h>
#include <GL/glext.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>

static int failed_stage, stage, destroys, surfaces, terminates, unbinds, swaps;
static int actual_major = 3, actual_minor = 2, actual_profile = GL_CONTEXT_CORE_PROFILE_BIT;
#define HIT() (++stage != failed_stage)

void probe_log(const char *stage_name, const char *message) { (void)stage_name; (void)message; }
EGLint eglGetError(void) { return EGL_BAD_MATCH; }
EGLDisplay eglGetDisplay(EGLNativeDisplayType native) { assert(native == EGL_DEFAULT_DISPLAY); return HIT() ? (EGLDisplay)1 : EGL_NO_DISPLAY; }
EGLBoolean eglInitialize(EGLDisplay d, EGLint *major, EGLint *minor) { assert(d); *major=1; *minor=5; return HIT(); }
const char *eglQueryString(EGLDisplay d, EGLint name) { (void)d; (void)name; return "mock"; }
EGLBoolean eglBindAPI(EGLenum api) { assert(api == EGL_OPENGL_API); return HIT(); }
EGLBoolean eglChooseConfig(EGLDisplay d, const EGLint *a, EGLConfig *c, EGLint n, EGLint *count) {
    (void)d; (void)n; assert(a[0] == EGL_SURFACE_TYPE); assert(a[2] == EGL_RENDERABLE_TYPE && a[3] == EGL_OPENGL_BIT);
    *count=1; *c=(EGLConfig)1; return HIT();
}
EGLSurface eglCreateWindowSurface(EGLDisplay d, EGLConfig c, EGLNativeWindowType window, const EGLint *a) {
    (void)d; (void)c; (void)a; assert(window == (EGLNativeWindowType)1); return HIT() ? (EGLSurface)1 : EGL_NO_SURFACE;
}
EGLContext eglCreateContext(EGLDisplay d, EGLConfig c, EGLContext share, const EGLint *a) {
    (void)d; (void)c; assert(share == EGL_NO_CONTEXT);
    assert(a[0] == EGL_CONTEXT_MAJOR_VERSION && a[1] == 3);
    assert(a[2] == EGL_CONTEXT_MINOR_VERSION && a[3] == 2);
    assert(a[4] == EGL_CONTEXT_OPENGL_PROFILE_MASK && a[5] == EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT);
    return HIT() ? (EGLContext)1 : EGL_NO_CONTEXT;
}
EGLBoolean eglMakeCurrent(EGLDisplay d, EGLSurface draw, EGLSurface read, EGLContext c) {
    (void)d; (void)read; if (c == EGL_NO_CONTEXT) { unbinds++; return EGL_TRUE; }
    assert(draw == (EGLSurface)1); return HIT();
}
EGLBoolean eglQuerySurface(EGLDisplay d, EGLSurface s, EGLint attribute, EGLint *value) {
    (void)d; (void)s; *value = attribute == EGL_WIDTH ? 1920 : 1080; return HIT();
}
EGLBoolean eglDestroyContext(EGLDisplay d, EGLContext c) { (void)d; (void)c; destroys++; return EGL_TRUE; }
EGLBoolean eglDestroySurface(EGLDisplay d, EGLSurface s) { (void)d; (void)s; surfaces++; return EGL_TRUE; }
EGLBoolean eglTerminate(EGLDisplay d) { (void)d; terminates++; return EGL_TRUE; }
EGLBoolean eglSwapBuffers(EGLDisplay d, EGLSurface s) { (void)d; (void)s; swaps++; return HIT(); }
static const GLubyte *fake_get_string(GLenum name) { (void)name; return (const GLubyte *)"mock GL"; }
static const GLubyte *fake_get_stringi(GLenum name, GLuint i) { (void)name; (void)i; return (const GLubyte *)"GL_mock"; }
static void fake_get_integer(GLenum name, GLint *value) {
    if (name == GL_MAJOR_VERSION) *value=actual_major;
    else if (name == GL_MINOR_VERSION) *value=actual_minor;
    else if (name == GL_CONTEXT_PROFILE_MASK) *value=actual_profile;
    else if (name == GL_NUM_EXTENSIONS) *value=1;
}
__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char *name) {
    if (!strcmp(name,"glGetString")) return (__eglMustCastToProperFunctionPointerType)fake_get_string;
    if (!strcmp(name,"glGetIntegerv")) return (__eglMustCastToProperFunctionPointerType)fake_get_integer;
    if (!strcmp(name,"glGetStringi")) return (__eglMustCastToProperFunctionPointerType)fake_get_stringi;
    return 0;
}

int main(void) {
    struct probe_egl ctx;
    for (failed_stage=1; failed_stage<=9; failed_stage++) {
        stage=destroys=surfaces=terminates=unbinds=0;
        assert(probe_egl_open(&ctx, 1280, 720) == -1);
        assert(ctx.display == EGL_NO_DISPLAY && ctx.surface == EGL_NO_SURFACE && ctx.context == EGL_NO_CONTEXT);
        assert(terminates == (failed_stage > 1));
        assert(surfaces == (failed_stage > 5));
        assert(destroys == (failed_stage > 6));
        probe_egl_close(&ctx);
        assert(terminates == (failed_stage > 1));
    }
    failed_stage=0; stage=destroys=surfaces=terminates=unbinds=0;
    actual_major=3; actual_minor=1;
    assert(probe_egl_open(&ctx, 1280, 720) == -1 && destroys == 1);
    actual_minor=2; actual_profile=0; destroys=0;
    assert(probe_egl_open(&ctx, 1280, 720) == -1 && destroys == 1);
    actual_profile=GL_CONTEXT_CORE_PROFILE_BIT;
    assert(probe_egl_open(&ctx, 1280, 720) == 0);
    assert(ctx.width == 1920 && ctx.height == 1080);
    assert(probe_egl_present(&ctx) == 0 && swaps == 1);
    failed_stage=stage+1;
    assert(probe_egl_present(&ctx) == -1);
    probe_egl_close(&ctx);
    probe_egl_close(&ctx);
    assert(destroys == 2 && surfaces == 3 && terminates == 3);
    puts("EGL lifecycle and acquisition failures passed");
    return 0;
}
