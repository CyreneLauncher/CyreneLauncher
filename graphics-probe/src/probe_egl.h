#ifndef PROBE_EGL_H
#define PROBE_EGL_H

#include <EGL/egl.h>

struct probe_egl {
    EGLDisplay display;
    EGLSurface surface;
    EGLContext context;
    unsigned width;
    unsigned height;
};

int probe_egl_open(struct probe_egl *ctx, unsigned width, unsigned height);
void probe_egl_close(struct probe_egl *ctx);
int probe_egl_present(struct probe_egl *ctx);

#endif
