#ifndef ORBIS_DISPLAY_SERVICE_H
#define ORBIS_DISPLAY_SERVICE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ORBIS_DISPLAY_ABI_VERSION 1u
#define ORBIS_DISPLAY_WIDTH 1280u
#define ORBIS_DISPLAY_HEIGHT 720u
#define ORBIS_DISPLAY_PIXEL_COUNT (ORBIS_DISPLAY_WIDTH * ORBIS_DISPLAY_HEIGHT)

/* Called by the presenter without holding a display or input lock. The
 * callback must copy console state under its short console lock, release that
 * lock, then fill the complete destination with canonical straight ARGB
 * pixels and return 0 on success. It must not call display or input service
 * functions. The destination is owned by the display service and is valid only
 * for the duration of the call. Register or clear it while display is stopped. */
typedef int32_t (*orbis_display_console_render_fn)(uint32_t *argb,
                                                   uint32_t width,
                                                   uint32_t height,
                                                   uint32_t stride_pixels,
                                                   void *context);

int32_t orbis_display_set_console_renderer(
    orbis_display_console_render_fn renderer, void *context);
int32_t orbis_display_start(void);
void orbis_display_stop(void);
int32_t orbis_display_set_desktop_enabled(int32_t enabled);
int32_t orbis_display_submit_argb(const uint32_t *pixels, size_t count,
                                  uint32_t width, uint32_t height,
                                  uint32_t stride_pixels, uint64_t sequence);
int32_t orbis_display_set_cursor(int32_t x, int32_t y, int32_t visible);
void orbis_display_mark_console_dirty(void);

#ifdef __cplusplus
}
#endif
#endif
