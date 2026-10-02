#include "probe_render.h"
#include <assert.h>
#include <stdio.h>

static int drawn, swapped, interval_calls, fail_draw, fail_swap;
void probe_log(const char *stage, const char *message) { (void)stage; (void)message; }
EGLBoolean eglSwapInterval(EGLDisplay d, EGLint interval) {
    assert(d == (EGLDisplay)1 && interval == 1);
    interval_calls++;
    return EGL_TRUE;
}
int probe_egl_present(struct probe_egl *ctx) {
    assert(ctx->surface == (EGLSurface)1);
    swapped++;
    return swapped == fail_swap ? -1 : 0;
}
static int draw_frame(void *unused, unsigned frame) {
    (void)unused;
    assert(frame == (unsigned)drawn);
    drawn++;
    return drawn == fail_draw ? -1 : 0;
}
int main(void) {
    struct probe_egl ctx = {.display=(EGLDisplay)1, .surface=(EGLSurface)1};
    double mean = -1, maximum = -1;
    assert(probe_render_present_frames(&ctx, 2, draw_frame, NULL, &mean, &maximum) == 0);
    assert(drawn == 600 && swapped == 600 && interval_calls == 1);
    assert(mean >= 0 && maximum >= mean);
    drawn=swapped=interval_calls=0; fail_draw=4;
    assert(probe_render_present_frames(&ctx, 600, draw_frame, NULL, &mean, &maximum) == -1);
    assert(drawn == 4 && swapped == 3);
    drawn=swapped=interval_calls=0; fail_draw=0; fail_swap=5;
    assert(probe_render_present_frames(&ctx, 600, draw_frame, NULL, &mean, &maximum) == -1);
    assert(drawn == 5 && swapped == 5);
    puts("frame orchestration passed");
    return 0;
}
