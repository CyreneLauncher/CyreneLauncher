/* OpenOrbis loader adapter for JDK native libraries linked into PRXs. */
#include <orbis/libkernel.h>
#include <fcntl.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static const char *loader_error;
typedef int (*orbis_initializer_fn)(void);
static const char *ORBis_NATIVE_LOG_PATH =
    "/data/orbis-jdk/logs/orbis-native.log";
static const char *ORBis_NATIVE_LOG_ROTATED_PATH =
    "/data/orbis-jdk/logs/orbis-native.log.1";
static const off_t ORBis_NATIVE_LOG_LIMIT = 256 * 1024;

static void orbis_trace(const char *text) {
    if (!text) return;
    int fd = open(ORBis_NATIVE_LOG_PATH,
                  O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd < 0) return;
    off_t size = lseek(fd, 0, SEEK_END);
    size_t length = strlen(text);
    if (size >= 0 && size + (off_t)length > ORBis_NATIVE_LOG_LIMIT) {
        close(fd);
        if (rename(ORBis_NATIVE_LOG_PATH, ORBis_NATIVE_LOG_ROTATED_PATH) < 0)
            unlink(ORBis_NATIVE_LOG_PATH);
        fd = open(ORBis_NATIVE_LOG_PATH,
                  O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (fd < 0) return;
    }
    write(fd, text, length);
    close(fd);
}

int __wrap_sceKernelDebugOutText(int channel, const char *text) {
    (void)channel;
    orbis_trace(text);
    return 0;
}

static int run_module_initializers(int32_t handle) {
    void *address = NULL;
    if (sceKernelDlsym(handle, "orbis_run_initializers", &address) < 0 || !address) {
        loader_error = "native initializer lookup failed";
        return -1;
    }
    if (((orbis_initializer_fn)address)() < 0) {
        loader_error = "native initializer recursion detected";
        return -1;
    }
    return 0;
}

void *__wrap_dlopen(const char *path, int flags) {
    (void)flags;
    if (!path) return (void *)(intptr_t)-1;
    char absolute[1024];
    if (path[0] != '/') {
        const char *prefix = "/app0/java/lib/";
        if (strlen(prefix) + strlen(path) >= sizeof(absolute)) {
            loader_error = "native library path too long";
            return NULL;
        }
        strcpy(absolute, prefix);
        strcat(absolute, path);
        path = absolute;
    }
    orbis_trace("orbis-native: loading ");
    orbis_trace(path);
    orbis_trace("\n");
    int result = 0;
    int32_t handle = (int32_t)sceKernelLoadStartModule(path, 0, NULL, 0, NULL, &result);
    if (handle < 0) {
        loader_error = "sceKernelLoadStartModule failed";
        return NULL;
    }
    if (run_module_initializers(handle) != 0) {
        return NULL;
    }
    return (void *)((uintptr_t)handle + 1);
}
void *__wrap_dlsym(void *handle, const char *name) {
    void *address = NULL;
    if (!handle || !name) return NULL;
    if (handle == (void *)(intptr_t)-1) {
        OrbisKernelModule modules[128];
        size_t count = 0;
        if (sceKernelGetModuleList(modules, sizeof(modules), &count) != 0) return NULL;
        if (count > 128) count = 128;
        for (size_t i = 0; i < count; ++i)
            if (sceKernelDlsym(modules[i], name, &address) >= 0 && address) return address;
    } else if (sceKernelDlsym((int32_t)((uintptr_t)handle - 1), name, &address) >= 0) {
        return address;
    }
    loader_error = "sceKernelDlsym failed";
    return NULL;
}
int __wrap_dlclose(void *handle) {
    if (!handle) return -1;
    if (handle == (void *)(intptr_t)-1) return 0;
    int result = 0;
    return sceKernelStopUnloadModule((int32_t)((uintptr_t)handle - 1), 0, NULL, 0, NULL, &result) < 0 ? -1 : 0;
}
char *__wrap_dlerror(void) {
    const char *error = loader_error;
    loader_error = NULL;
    return (char *)error;
}
