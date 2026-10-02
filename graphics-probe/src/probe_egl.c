#include "probe_egl.h"
#include "probe_control.h"
#include "probe_log.h"
#include <GL/gl.h>
#include <GL/glext.h>
#include <stdio.h>
#include <string.h>

typedef const GLubyte *(*get_string_fn)(GLenum);
typedef void (*get_integer_fn)(GLenum, GLint *);
typedef const GLubyte *(*get_stringi_fn)(GLenum, GLuint);

static void failure(const char *stage)
{
    char message[96];
    snprintf(message, sizeof message, "%s failed (EGL error 0x%04x)", stage,
             (unsigned)eglGetError());
    probe_log("EGL_ERROR", message);
}

void probe_egl_close(struct probe_egl *ctx)
{
    if (!ctx) return;
    if (ctx->display != EGL_NO_DISPLAY) {
        if (ctx->context != EGL_NO_CONTEXT)
            eglMakeCurrent(ctx->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (ctx->context != EGL_NO_CONTEXT) eglDestroyContext(ctx->display, ctx->context);
        if (ctx->surface != EGL_NO_SURFACE) eglDestroySurface(ctx->display, ctx->surface);
        eglTerminate(ctx->display);
    }
    memset(ctx, 0, sizeof *ctx);
}

int probe_egl_open(struct probe_egl *ctx, unsigned width, unsigned height)
{
    EGLint major, minor, count, actual_width, actual_height;
    EGLConfig config;
    GLint gl_major = 0, gl_minor = 0, profile = 0, extensions = 0;
    get_string_fn get_string;
    get_integer_fn get_integer;
    get_stringi_fn get_stringi;
    char message[320];
    static const EGLint config_attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_NONE
    };
    static const EGLint context_attributes[] = {
        EGL_CONTEXT_MAJOR_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION, 2,
        EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
        EGL_NONE
    };

    if (!ctx || !width || !height) {
        probe_log("EGL_ERROR", "invalid context or dimensions");
        return -1;
    }
    memset(ctx, 0, sizeof *ctx);
    ctx->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (ctx->display == EGL_NO_DISPLAY) { failure("eglGetDisplay"); goto fail; }
    if (!eglInitialize(ctx->display, &major, &minor)) { failure("eglInitialize"); goto fail; }
    snprintf(message, sizeof message, "EGL %d.%d vendor=%s client_api=%s", major, minor,
             eglQueryString(ctx->display, EGL_VENDOR) ? eglQueryString(ctx->display, EGL_VENDOR) : "unknown",
             eglQueryString(ctx->display, EGL_CLIENT_APIS) ? eglQueryString(ctx->display, EGL_CLIENT_APIS) : "unknown");
    probe_log("EGL", message);
    snprintf(message, sizeof message, "EGL extensions=%.240s",
             eglQueryString(ctx->display, EGL_EXTENSIONS) ?
             eglQueryString(ctx->display, EGL_EXTENSIONS) : "unknown");
    probe_log("EGL_EXTENSIONS", message);
    if (!eglBindAPI(EGL_OPENGL_API)) { failure("eglBindAPI(EGL_OPENGL_API)"); goto fail; }
    if (!eglChooseConfig(ctx->display, config_attributes, &config, 1, &count) || count < 1) {
        failure("eglChooseConfig desktop OpenGL"); goto fail;
    }
    /* The Orbis EGL core rejects NULL; platform_orbis uses token 1 for scanout. */
    if (probe_control_requested("PROBE_FAIL_SURFACE",
                                "/data/orbis-jdk/graphics-probe/fail-surface")) {
        probe_log("EGL_NEGATIVE", "controlled surface acquisition failure requested"); goto fail;
    }
    ctx->surface = eglCreateWindowSurface(ctx->display, config, (EGLNativeWindowType)1, NULL);
    if (ctx->surface == EGL_NO_SURFACE) { failure("eglCreateWindowSurface"); goto fail; }
    ctx->context = eglCreateContext(ctx->display, config, EGL_NO_CONTEXT, context_attributes);
    if (ctx->context == EGL_NO_CONTEXT) { failure("eglCreateContext OpenGL 3.2 core"); goto fail; }
    if (!eglMakeCurrent(ctx->display, ctx->surface, ctx->surface, ctx->context)) {
        failure("eglMakeCurrent"); goto fail;
    }
    get_string = (get_string_fn)eglGetProcAddress("glGetString");
    get_integer = (get_integer_fn)eglGetProcAddress("glGetIntegerv");
    get_stringi = (get_stringi_fn)eglGetProcAddress("glGetStringi");
    if (!get_string || !get_integer || !get_stringi) {
        probe_log("GL_ERROR", "desktop GL entry points unavailable"); goto fail;
    }
    get_integer(GL_MAJOR_VERSION, &gl_major);
    get_integer(GL_MINOR_VERSION, &gl_minor);
    get_integer(GL_CONTEXT_PROFILE_MASK, &profile);
    if (gl_major < 3 || (gl_major == 3 && gl_minor < 2) ||
        !(profile & GL_CONTEXT_CORE_PROFILE_BIT)) {
        snprintf(message, sizeof message, "desktop OpenGL 3.2 core unavailable: actual %d.%d profile=0x%x",
                 gl_major, gl_minor, (unsigned)profile);
        probe_log("GL_ERROR", message); goto fail;
    }
    if (!eglQuerySurface(ctx->display, ctx->surface, EGL_WIDTH, &actual_width) ||
        !eglQuerySurface(ctx->display, ctx->surface, EGL_HEIGHT, &actual_height)) {
        failure("eglQuerySurface dimensions"); goto fail;
    }
    if (actual_width <= 0 || actual_height <= 0) {
        probe_log("EGL_ERROR", "scanout has invalid dimensions"); goto fail;
    }
    ctx->width = (unsigned)actual_width;
    ctx->height = (unsigned)actual_height;
    snprintf(message, sizeof message, "requested=%ux%u scanout=%ux%u GL=%s profile=0x%x",
             width, height, ctx->width, ctx->height,
             get_string(GL_VERSION) ? (const char *)get_string(GL_VERSION) : "unknown",
             (unsigned)profile);
    probe_log("GL", message);
    snprintf(message, sizeof message, "vendor=%s renderer=%s GLSL=%s",
             get_string(GL_VENDOR) ? (const char *)get_string(GL_VENDOR) : "unknown",
             get_string(GL_RENDERER) ? (const char *)get_string(GL_RENDERER) : "unknown",
             get_string(GL_SHADING_LANGUAGE_VERSION) ? (const char *)get_string(GL_SHADING_LANGUAGE_VERSION) : "unknown");
    probe_log("GL_DRIVER", message);
    snprintf(message, sizeof message, "Mesa source=1a59cc147b54e3970814b37313e2f27357672e9d reported GL version/revision=%.180s",
             get_string(GL_VERSION) ? (const char *)get_string(GL_VERSION) : "unknown");
    probe_log("DRIVER_REVISION", message);
    probe_log("GPU_POLICY", "driver renderer/chip string is reported as supplied; Liverpool on Pro remains unverified base behavior");
    get_integer(GL_NUM_EXTENSIONS, &extensions);
    snprintf(message, sizeof message, "extension_count=%d first=%s", extensions,
             extensions > 0 && get_stringi(GL_EXTENSIONS, 0) ?
             (const char *)get_stringi(GL_EXTENSIONS, 0) : "none");
    probe_log("GL_EXTENSIONS", message);
    return 0;
fail:
    probe_egl_close(ctx);
    return -1;
}

int probe_egl_present(struct probe_egl *ctx)
{
    if (!ctx || ctx->display == EGL_NO_DISPLAY || ctx->surface == EGL_NO_SURFACE ||
        ctx->context == EGL_NO_CONTEXT) return -1;
    if (!eglSwapBuffers(ctx->display, ctx->surface)) {
        failure("eglSwapBuffers");
        return -1;
    }
    return 0;
}
