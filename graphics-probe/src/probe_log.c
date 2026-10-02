#include "probe_log.h"
#include <stdio.h>
#include <string.h>

#ifdef __ORBIS__
#include <orbis/libkernel.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#ifndef PROBE_BUILD_ID
#define PROBE_BUILD_ID "mesa-1a59cc147b54"
#endif

static int file_fd = -2;
static int file_warning_sent;

void probe_log(const char *stage, const char *message)
{
    char line[512];
    int count = snprintf(line, sizeof line, "[%s] %s: %s\n", PROBE_BUILD_ID,
                         stage ? stage : "unknown", message ? message : "(null)");
    size_t length;
    if (count < 0) return;
    length = (size_t)count < sizeof line ? (size_t)count : sizeof line - 1;
    if ((size_t)count >= sizeof line) line[length - 1] = '\n';
    line[length] = 0;
#ifdef __ORBIS__
    /* UART is independent of all filesystem operations. */
    sceKernelDebugOutText(0, line);
    if (file_fd == -2) {
        mkdir("/data/orbis-jdk", 0777);
        mkdir("/data/orbis-jdk/graphics-probe", 0777);
        file_fd = open("/data/orbis-jdk/graphics-probe/probe.log",
                       O_WRONLY | O_CREAT | O_APPEND, 0666);
    }
    if (file_fd >= 0) {
        if (write(file_fd, line, length) != (ssize_t)length && !file_warning_sent) {
            file_warning_sent = 1;
            sceKernelDebugOutText(0, "graphics-probe: file log write failed; UART remains active\n");
        }
    } else if (!file_warning_sent) {
        file_warning_sent = 1;
        sceKernelDebugOutText(0, "graphics-probe: file log open failed; UART remains active\n");
    }
#else
    fwrite(line, 1, length, stderr);
#endif
}

void probe_log_close(void)
{
#ifdef __ORBIS__
    if (file_fd >= 0) close(file_fd);
#endif
    file_fd = -2;
    file_warning_sent = 0;
}
