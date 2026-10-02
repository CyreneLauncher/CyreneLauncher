/* OpenOrbis loader adapter for JDK native libraries linked into PRXs. */
#include <orbis/libkernel.h>
#include <fcntl.h>
#include <errno.h>
#include <time.h>
#include <pthread.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Each PRX has a separate opt-in debug destination. No descriptor is retained. */
static char debug_log_path[512];
static void orbis_debug(const char *text) {
    int saved_errno = errno;
    if (debug_log_path[0] && text) {
        char entry[1536];
        int length = snprintf(entry, sizeof(entry), "native time=%lld thread=%lu module=%p %s",
                              (long long)time(NULL), (unsigned long)pthread_self(),
                              (void *)debug_log_path, text);
        if (length > 0) {
            size_t remaining = (size_t)length < sizeof(entry) ?
                               (size_t)length : sizeof(entry) - 2;
            if (!remaining || entry[remaining - 1] != '\n')
                entry[remaining++] = '\n';
            int fd = open(debug_log_path, O_WRONLY | O_CREAT | O_APPEND, 0666);
            if (fd >= 0) {
                off_t debug_size = lseek(fd, 0, SEEK_END);
                if (debug_size < 0 || debug_size >= 8 * 1024 * 1024) {
                    close(fd);
                    errno = saved_errno;
                    return;
                }
                const char *cursor = entry;
                while (remaining) {
                    ssize_t written = write(fd, cursor, remaining);
                    if (written < 0 && errno == EINTR) continue;
                    if (written <= 0) break;
                    cursor += written;
                    remaining -= (size_t)written;
                }
                close(fd);
            }
        }
    }
    errno = saved_errno;
}

__attribute__((visibility("hidden")))
void orbis_debug_boundary(const char *text) {
    orbis_debug(text);
}

__attribute__((visibility("default")))
int orbis_set_debug_log(const char *path) {
    if (!path || (path[0] && path[0] != '/') || strlen(path) >= sizeof(debug_log_path))
        return -1;
    // The JDK's generic native I/O diagnostics share this per-module switch.
    if (path[0] ? setenv("ORBIS_NATIVE_IO_TRACE", "1", 1) < 0 :
                  unsetenv("ORBIS_NATIVE_IO_TRACE") < 0)
        return -1;
    strcpy(debug_log_path, path);
    orbis_debug("debug logging enabled\n");
    return 0;
}

static int debug_result(const char *operation, int result) {
    if (!debug_log_path[0]) return result;
    int saved_errno = errno;
    char entry[192];
    snprintf(entry, sizeof(entry), "%s returned result=%d\n", operation, result);
    orbis_debug(entry);
    errno = saved_errno;
    return result;
}

static const char *loader_error;
/* Each PRX owns this state (-Bsymbolic); no launcher environment is shared. */
/* Configuration is immutable after success; failed seeding is terminal. */
static int runtime_configuration_state;
static char runtime_home[1024];
static char runtime_working_directory[1024];
static char runtime_temporary_directory[1024];
__attribute__((visibility("default")))
int orbis_configure_runtime_v2(const char *java_home, const char *working_directory,
                            const char *temporary_directory) {
    orbis_debug("runtime configuration entered\n");
    if (!java_home || java_home[0] != '/' ||
        strlen(java_home) + sizeof("/lib/") > sizeof(runtime_home) ||
        !working_directory || working_directory[0] != '/' ||
        strlen(working_directory) >= sizeof(runtime_working_directory) ||
        !temporary_directory || temporary_directory[0] != '/' ||
        strlen(temporary_directory) >= sizeof(runtime_temporary_directory)) {
        loader_error = "invalid native runtime home, working or temporary directory";
        return debug_result("runtime configuration", -1);
    }
    if (runtime_configuration_state < 0) {
        loader_error = "native runtime environment initialization previously failed";
        return debug_result("runtime configuration", -1);
    }
    if (runtime_configuration_state > 0) {
        if (strcmp(runtime_home, java_home) == 0 &&
            strcmp(runtime_working_directory, working_directory) == 0 &&
            strcmp(runtime_temporary_directory, temporary_directory) == 0)
            return debug_result("runtime configuration", 0);
        loader_error = "native runtime configuration is immutable; restart to switch paths";
        return debug_result("runtime configuration", -1);
    }
    if (setenv("ORBIS_JAVA_USER_DIR", working_directory, 1) < 0 ||
        setenv("ORBIS_JAVA_TMP_DIR", temporary_directory, 1) < 0 ||
        setenv("TMPDIR", temporary_directory, 1) < 0 ||
        setenv("ORBIS_JAVA_HOME", java_home, 1) < 0) {
        runtime_configuration_state = -1;
        loader_error = "native runtime environment initialization failed; restart required";
        return debug_result("runtime configuration", -1);
    }
    strcpy(runtime_home, java_home);
    strcpy(runtime_working_directory, working_directory);
    strcpy(runtime_temporary_directory, temporary_directory);
    runtime_configuration_state = 1;
    return debug_result("runtime configuration", 0);
}

typedef int (*orbis_initializer_fn)(void);
static const char *ORBis_NATIVE_LOG_PATH =
    "/data/orbis-jdk/logs/orbis-native.log";
static const char *ORBis_NATIVE_LOG_ROTATED_PATH =
    "/data/orbis-jdk/logs/orbis-native.log.1";
static const off_t ORBis_NATIVE_LOG_LIMIT = 256 * 1024;
static const char *ORBis_VMMAP_LOG_PATH =
    "/data/orbis-jdk/logs/orbis-vmmap.log";

static void orbis_trace(const char *text) {
    if (!text) return;
    orbis_debug(text);
    if (strncmp(text, "orbis-vmmap ", 12) == 0) {
        int trace_fd = open(ORBis_VMMAP_LOG_PATH,
                            O_WRONLY | O_CREAT | O_APPEND, 0666);
        if (trace_fd < 0) return;
        write(trace_fd, text, strlen(text));
        close(trace_fd);
        return;
    }
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

static int debug_dlsym(int32_t handle, const char *name, void **address) {
    if (!debug_log_path[0]) return sceKernelDlsym(handle, name, address);
    int saved_errno = errno;
    char entry[256];
    snprintf(entry, sizeof(entry), "dlsym entered handle=%d name=%s\n", handle, name);
    orbis_debug(entry);
    errno = saved_errno;
    int result = sceKernelDlsym(handle, name, address);
    saved_errno = errno;
    snprintf(entry, sizeof(entry), "dlsym returned handle=%d name=%s result=%d address=%p\n",
             handle, name, result, result >= 0 ? *address : NULL);
    orbis_debug(entry);
    errno = saved_errno;
    return result;
}

static int run_module_initializers(int32_t handle) {
    void *address = NULL;
    if (debug_log_path[0]) {
        if (debug_dlsym(handle, "orbis_set_debug_log", &address) < 0 || !address) {
            loader_error = "native debug configuration lookup failed";
            return -1;
        }
        orbis_debug("child debug configuration entered\n");
        int result = ((int (*)(const char *))address)(debug_log_path);
        debug_result("child debug configuration", result);
        if (result < 0) {
            loader_error = "native debug configuration failed";
            return -1;
        }
    }
    address = NULL;
    if (runtime_configuration_state != 1 ||
        debug_dlsym(handle, "orbis_configure_runtime_v2", &address) < 0 || !address) {
        loader_error = "native runtime configuration failed";
        return -1;
    }
    orbis_debug("child runtime configuration entered\n");
    int result = ((int (*)(const char *, const char *, const char *))address)(
        runtime_home, runtime_working_directory, runtime_temporary_directory);
    debug_result("child runtime configuration", result);
    if (result < 0) {
        loader_error = "native runtime configuration failed";
        return -1;
    }
    address = NULL;
    if (debug_dlsym(handle, "orbis_run_initializers", &address) < 0 || !address) {
        loader_error = "native initializer lookup failed";
        return -1;
    }
    orbis_debug("child initializer invocation entered\n");
    result = ((orbis_initializer_fn)address)();
    debug_result("child initializer invocation", result);
    if (result < 0) {
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
        if (!runtime_home[0]) {
            loader_error = "native runtime home is not configured";
            return NULL;
        }
        if (strlen(runtime_home) + strlen("/lib/") + strlen(path) >= sizeof(absolute)) {
            loader_error = "native library path too long";
            return NULL;
        }
        strcpy(absolute, runtime_home);
        strcat(absolute, "/lib/");
        strcat(absolute, path);
        path = absolute;
    }
    if (strlen(path) >= sizeof(absolute)) {
        loader_error = "native library path too long";
        return NULL;
    }
    orbis_trace("orbis-native: loading ");
    orbis_trace(path);
    orbis_trace("\n");
    orbis_trace("orbis-native: module load entered\n");
    int result = 0;
    int32_t handle = (int32_t)sceKernelLoadStartModule(path, 0, NULL, 0, NULL, &result);
    char probe[160];
    snprintf(probe, sizeof(probe),
             "orbis-native: module load returned handle=%d start=%d\n",
             handle, result);
    orbis_trace(probe);
    if (handle < 0) {
        loader_error = "sceKernelLoadStartModule failed";
        return NULL;
    }
    orbis_trace("orbis-native: module initializers entered\n");
    if (run_module_initializers(handle) != 0) {
        orbis_trace("orbis-native: module initializers returned failure\n");
        return NULL;
    }
    orbis_trace("orbis-native: module initializers returned success\n");
    return (void *)((uintptr_t)handle + 1);
}
void *__wrap_dlsym(void *handle, const char *name) {
    void *address = NULL;
    if (!handle || !name) return NULL;
    char probe[192];
    snprintf(probe, sizeof(probe), "orbis-native: symbol lookup entered name=%s\n", name);
    orbis_trace(probe);
    if (handle == (void *)(intptr_t)-1) {
        OrbisKernelModule modules[128];
        size_t count = 0;
        orbis_debug("native lookup module list entered\n");
        int list_result = sceKernelGetModuleList(modules, sizeof(modules), &count);
        debug_result("native lookup module list", list_result);
        if (list_result != 0) {
            orbis_debug("native lookup returned failure (module list)\n");
            return NULL;
        }
        if (count > 128) count = 128;
        for (size_t i = 0; i < count; ++i) {
            if (debug_dlsym(modules[i], name, &address) >= 0 && address) {
                snprintf(probe, sizeof(probe),
                         "orbis-native: symbol lookup returned name=%s address=%p\n",
                         name, address);
                orbis_trace(probe);
                return address;
            }
        }
    } else if (debug_dlsym((int32_t)((uintptr_t)handle - 1), name, &address) >= 0) {
        snprintf(probe, sizeof(probe),
                 "orbis-native: symbol lookup returned name=%s address=%p\n",
                 name, address);
        orbis_trace(probe);
        return address;
    }
    snprintf(probe, sizeof(probe), "orbis-native: symbol lookup returned name=%s address=%p\n",
             name, address);
    orbis_trace(probe);
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
