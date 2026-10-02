#include "probe_egl.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int probe_app_main(void);

static const char *expected_name;

int probe_egl_open(struct probe_egl *ctx, unsigned width, unsigned height)
{
    const char *actual = getenv("MESA_PROCESS_NAME");
    (void)ctx;
    (void)width;
    (void)height;
    return actual && strcmp(actual, expected_name) == 0 ? 0 : -1;
}

void probe_egl_close(struct probe_egl *ctx) { (void)ctx; }
int probe_render_run(struct probe_egl *ctx, unsigned frames)
{
    (void)ctx;
    return frames == 600 ? 0 : -1;
}
void probe_log(const char *stage, const char *message)
{
    (void)stage;
    (void)message;
}
void probe_log_close(void) { }

int main(int argc, char **argv)
{
    if (argc != 2) return 2;
    if (strcmp(argv[1], "default") == 0) {
        unsetenv("MESA_PROCESS_NAME");
        expected_name = "orbis-mesa-graphics-probe";
    } else {
        setenv("MESA_PROCESS_NAME", argv[1], 1);
        expected_name = argv[1];
    }
    return probe_app_main();
}
