#include "probe_egl.h"
#include "probe_log.h"
#include "probe_render.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef __ORBIS__
#include <orbis/libkernel.h>
#endif

#ifndef PROBE_BUILD_ID
#define PROBE_BUILD_ID "mesa-1a59cc147b54"
#endif

int main(void)
{
    struct probe_egl ctx = {0};
    char message[160];
    int result = 1;

    if (getenv("MESA_PROCESS_NAME") == NULL &&
        setenv("MESA_PROCESS_NAME", "orbis-mesa-graphics-probe", 0) != 0) {
        probe_log("PROCESS_NAME", "could not set Mesa process name");
        goto done;
    }

    snprintf(message, sizeof message, "build=%s compiler=%s target=%s", PROBE_BUILD_ID,
#ifdef __clang__
             __clang_version__,
#else
             "other",
#endif
#ifdef __ORBIS__
             "x86_64-pc-freebsd12-elf");
#else
             "host");
#endif
    probe_log("START", message);
#ifdef __ORBIS__
    {
        OrbisKernelSwVersion version;
        memset(&version, 0, sizeof version);
        version.Size = sizeof version;
        if (sceKernelGetSystemSwVersion(&version) == 0) {
            snprintf(message, sizeof message, "firmware=0x%08x string=%.28s",
                     version.Version, version.VersionString);
            probe_log("FIRMWARE", message);
        } else {
            probe_log("FIRMWARE", "SDK firmware query failed");
        }
    }
#endif
    probe_log("MODE", "base/Pro execution mode unavailable from verified ABI; report separately on device");
    if (probe_egl_open(&ctx, 1280, 720) != 0) goto done;
    if (probe_render_run(&ctx, 600) != 0) goto done;
    result = 0;
done:
    probe_egl_close(&ctx);
    probe_log("SHUTDOWN", result ? "failed" : "clean");
    probe_log_close();
    return result;
}
