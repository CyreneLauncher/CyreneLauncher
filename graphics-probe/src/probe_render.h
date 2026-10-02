#ifndef PROBE_RENDER_H
#define PROBE_RENDER_H

#include "probe_egl.h"

int probe_pixel_matches(const unsigned char actual[4], const unsigned char expected[4], unsigned tolerance);
int probe_render_present_frames(struct probe_egl *ctx, unsigned frames,
                                int (*draw_frame)(void *, unsigned), void *opaque,
                                double *cpu_mean_ms, double *cpu_max_ms);
int probe_render_run(struct probe_egl *ctx, unsigned frames);

#endif
