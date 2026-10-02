#include <jni.h>
#include <orbis/libkernel.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "tools/orbis_runtime.h"
#include "tools/runner_config.h"
#include "tools/orbis_stat.h"
#include "tools/orbis_mapping_probe.h"
#include "launcher_profile.h"
#include "tools/orbis_screen_console.h"
#include "tools/orbis_desktop_bridge.h"
#include "tools/orbis_gpu_input_bridge.h"
#include "tools/orbis_input_service.h"
#include "tools/orbis_desktop_preflight.h"

struct ScreenConsoleLifetime {
    ~ScreenConsoleLifetime() { orbis_screen_console_shutdown(); }
};

typedef jint (*create_vm_fn)(JavaVM **, void **, void *);
typedef int (*run_initializers_fn)(void);

enum launcher_probe_mode {
    PROBE_MODE_JAVA = 0,
    PROBE_MODE_ALLOCATOR = 1,
    PROBE_MODE_FATAL_ALLOCATION = 2
};

#ifndef ORBIS_JAVA_BUILD_VERSION
#define ORBIS_JAVA_BUILD_VERSION "dev"
#endif
#ifndef ORBIS_JAVA_BUILD_KIND
#define ORBIS_JAVA_BUILD_KIND "generic main and exit modes"
#endif

static void debug_text(const char *text);
static void startup_error(const char *path);
static RunnerConfig runner_config;
static char runner_jvm_path[1024];
static const char *ORBis_LOG_PATH = ORBIS_PROFILE_LOG_PATH;
static const char *ORBis_LOG_ROTATED_PATH = ORBIS_PROFILE_ROTATED_LOG_PATH;
static const off_t ORBis_LOG_LIMIT = 256 * 1024;
static int orbis_log_fd = -1;
static pthread_mutex_t startup_watchdog_lock = PTHREAD_MUTEX_INITIALIZER;
static int startup_vm_pending;
static int startup_dump_after_seconds;
static void (*startup_previous_sigquit_handler)(int);
static const char *RUNNER_DEBUG_LOG = RUNNER_ROOT "/runner-debug.log";
static int runner_debug_ready;
static unsigned long runner_debug_sequence;
static pthread_mutex_t runner_debug_lock = PTHREAD_MUTEX_INITIALIZER;

static void runner_debug(const char *format, ...) {
    if (!runner_debug_ready) return;
    int saved_errno = errno;
    pthread_mutex_lock(&runner_debug_lock);
    char message[2048], line[2304];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    int length = snprintf(line, sizeof(line), "%lld launcher #%lu thread=%llu %s\n",
        (long long)time(NULL), ++runner_debug_sequence,
        (unsigned long long)(uintptr_t)pthread_self(), message);
    int fd = open(RUNNER_DEBUG_LOG, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd >= 0) {
        off_t debug_size = lseek(fd, 0, SEEK_END);
        if (debug_size < 0 || debug_size >= 8 * 1024 * 1024) {
            close(fd);
            pthread_mutex_unlock(&runner_debug_lock);
            errno = saved_errno;
            return;
        }
        size_t remaining = length > 0 ? (size_t)length : 0;
        if (remaining >= sizeof(line)) remaining = sizeof(line)-1;
        const char *cursor = line;
        while (remaining) {
            ssize_t n = write(fd, cursor, remaining);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) break;
            cursor += n; remaining -= n;
        }
        close(fd);
    } else {
        sceKernelDebugOutText(0, "orbis-java: cannot append runner-debug.log\n");
    }
    pthread_mutex_unlock(&runner_debug_lock);
    errno = saved_errno;
}

// These wrappers trace launcher filesystem/environment operations. The logger
// above uses the underlying calls, so its own writes cannot recurse.
static int debug_open(const char *path, int flags, mode_t mode = 0) {
    runner_debug("ENTER open path=%s flags=0x%x", path, flags);
    int result = open(path, flags, mode); int error = errno;
    runner_debug("RETURN open result=%d errno=%d", result, result < 0 ? error : 0);
    errno = error; return result;
}
static int debug_close(int fd) {
    runner_debug("ENTER close fd=%d", fd);
    int result = close(fd); int error = errno;
    runner_debug("RETURN close result=%d errno=%d", result, result < 0 ? error : 0);
    errno = error; return result;
}
static int debug_stat(const char *path, OrbisRunnerStat *status) {
    runner_debug("ENTER stat path=%s", path);
    int result = stat(path, reinterpret_cast<struct stat *>(status)); int error = errno;
    runner_debug("RETURN stat result=%d errno=%d mode=0%o size=%lld", result,
        result < 0 ? error : 0, result < 0 ? 0 : (unsigned)status->mode,
        result < 0 ? 0LL : (long long)status->size);
    errno = error; return result;
}
static int debug_mkdir(const char *path, mode_t mode) {
    runner_debug("ENTER mkdir path=%s", path);
    int result = mkdir(path, mode); int error = errno;
    runner_debug("RETURN mkdir result=%d errno=%d", result, result < 0 ? error : 0);
    errno = error; return result;
}
static int debug_chdir(const char *path) {
    runner_debug("ENTER chdir path=%s", path);
    int result = chdir(path); int error = errno;
    runner_debug("RETURN chdir result=%d errno=%d", result, result < 0 ? error : 0);
    errno = error; return result;
}
static int debug_setenv(const char *key, const char *value, int overwrite) {
    runner_debug("ENTER setenv key=%s value=%s", key, value);
    int result = setenv(key, value, overwrite); int error = errno;
    runner_debug("RETURN setenv result=%d errno=%d", result, result < 0 ? error : 0);
    errno = error; return result;
}
static ssize_t debug_read(int fd, void *data, size_t size) {
    runner_debug("ENTER read fd=%d size=%zu", fd, size);
    ssize_t result = read(fd, data, size); int error = errno;
    runner_debug("RETURN read result=%lld errno=%d", (long long)result, result < 0 ? error : 0);
    errno = error; return result;
}
static ssize_t debug_write(int fd, const void *data, size_t size) {
    runner_debug("ENTER write fd=%d size=%zu", fd, size);
    ssize_t result = write(fd, data, size); int error = errno;
    runner_debug("RETURN write result=%lld errno=%d", (long long)result, result < 0 ? error : 0);
    errno = error; return result;
}
static int debug_unlink(const char *path) {
    runner_debug("ENTER unlink path=%s", path);
    int result = unlink(path); int error = errno;
    runner_debug("RETURN unlink result=%d errno=%d", result, result < 0 ? error : 0);
    errno = error; return result;
}
static int debug_rename(const char *from, const char *to) {
    runner_debug("ENTER rename from=%s to=%s", from, to);
    int result = rename(from, to); int error = errno;
    runner_debug("RETURN rename result=%d errno=%d", result, result < 0 ? error : 0);
    errno = error; return result;
}
#define read(...) debug_read(__VA_ARGS__)
#define write(...) debug_write(__VA_ARGS__)
#define unlink(...) debug_unlink(__VA_ARGS__)
#define rename(...) debug_rename(__VA_ARGS__)
#define open(...) debug_open(__VA_ARGS__)
#define close(...) debug_close(__VA_ARGS__)
#define stat(...) debug_stat(__VA_ARGS__)
#define mkdir(...) debug_mkdir(__VA_ARGS__)
#define chdir(...) debug_chdir(__VA_ARGS__)
#define setenv(...) debug_setenv(__VA_ARGS__)

static void orbis_log_line(const char *level, const char *component,
                           const char *format, ...);

static void set_startup_vm_pending(int pending) {
    pthread_mutex_lock(&startup_watchdog_lock);
    startup_vm_pending = pending;
    pthread_mutex_unlock(&startup_watchdog_lock);
}

static int is_startup_vm_pending(void) {
    pthread_mutex_lock(&startup_watchdog_lock);
    int pending = startup_vm_pending;
    pthread_mutex_unlock(&startup_watchdog_lock);
    return pending;
}

static int startup_dump_delay(void) {
    char path[512];
    int length = snprintf(path, sizeof(path), "%s/startup-dump-after-seconds.txt",
                          runner_config.working_directory);
    if (length < 0 || length >= (int)sizeof(path)) return -1;
    FILE *file = fopen(path, "r");
    if (file == NULL) return errno == ENOENT ? 0 : -1;
    char line[32];
    if (fgets(line, sizeof(line), file) == NULL) {
        fclose(file);
        return -1;
    }
    if (!feof(file) && strchr(line, '\n') == NULL) {
        fclose(file);
        return -1;
    }
    int read_error = ferror(file);
    fclose(file);
    if (read_error) return -1;

    char *end = NULL;
    errno = 0;
    long seconds = strtol(line, &end, 10);
    if (errno != 0 || end == line || seconds < 10 || seconds > 3600)
        return -1;
    while (*end == ' ' || *end == '\t' || *end == '\r' || *end == '\n') ++end;
    return *end == '\0' ? (int)seconds : -1;
}

static void *startup_dump_watchdog(void *unused) {
    (void)unused;
    struct timespec pause = {1, 0};
    for (int elapsed = 0; elapsed < startup_dump_after_seconds; ++elapsed) {
        if (!is_startup_vm_pending()) return NULL;
        while (nanosleep(&pause, &pause) < 0 && errno == EINTR) {}
        pause.tv_sec = 1;
        pause.tv_nsec = 0;
    }
    if (!is_startup_vm_pending()) return NULL;

    struct sigaction current;
    if (sigaction(SIGQUIT, NULL, &current) < 0 ||
        current.sa_handler == SIG_DFL || current.sa_handler == SIG_IGN ||
        current.sa_handler == startup_previous_sigquit_handler) {
        orbis_log_line("WARN", "startup-watchdog",
                       "timeout=%d seconds; HotSpot SIGQUIT handler not installed",
                       startup_dump_after_seconds);
        return NULL;
    }

    orbis_log_line("WARN", "startup-watchdog",
                   "timeout=%d seconds; requesting HotSpot thread dump",
                   startup_dump_after_seconds);
    if (kill(getpid(), SIGQUIT) < 0)
        orbis_log_line("ERROR", "startup-watchdog",
                       "SIGQUIT failed errno=%d", errno);
    return NULL;
}

static int mkdir_path(const char *path) {
    char current[512];
    size_t length = strlen(path);
    if (length == 0 || length >= sizeof(current))
        return -1;
    memcpy(current, path, length + 1);
    for (size_t i = 1; i <= length; ++i) {
        if (current[i] != '/' && current[i] != '\0')
            continue;
        char saved = current[i];
        current[i] = '\0';
        if (mkdir(current, 0777) < 0 && errno != EEXIST)
            return -1;
        current[i] = saved;
    }
    return 0;
}

static int prepare_profile_directories(void) {
    if (mkdir_path("/data/orbis-jdk/logs") < 0)
        return -1;
    if (strcmp(runner_config.working_directory, "/app0") != 0 &&
        mkdir_path(runner_config.working_directory) < 0) {
        startup_error(runner_config.working_directory);
        return -1;
    }
    if (mkdir_path(runner_config.temporary_directory) < 0) {
        startup_error(runner_config.temporary_directory);
        return -1;
    }
    return 0;
}

static void orbis_log_open(void) {
    mkdir("/data/orbis-jdk", 0777);
    mkdir("/data/orbis-jdk/logs", 0777);
    orbis_log_fd = open(ORBis_LOG_PATH, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (orbis_log_fd < 0)
        return;
    off_t size = lseek(orbis_log_fd, 0, SEEK_END);
    if (size >= ORBis_LOG_LIMIT) {
        close(orbis_log_fd);
        orbis_log_fd = -1;
        if (rename(ORBis_LOG_PATH, ORBis_LOG_ROTATED_PATH) < 0)
            unlink(ORBis_LOG_PATH);
        orbis_log_fd = open(ORBis_LOG_PATH,
            O_WRONLY | O_CREAT | O_TRUNC, 0666);
    }
}

static void orbis_log_line(const char *level, const char *component,
                           const char *format, ...) {
    char message[768];
    va_list args;
    va_start(args, format);
    int message_length = vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    if (message_length < 0)
        return;
    if (message_length >= (int)sizeof(message))
        message_length = sizeof(message) - 1;

    runner_debug("%s %s %.*s", level, component, message_length, message);
    if (orbis_log_fd < 0) return;
    time_t now = time(NULL);
    struct tm utc;
    memset(&utc, 0, sizeof(utc));
    gmtime_r(&now, &utc);
    char line[1024];
    int line_length = snprintf(line, sizeof(line),
        "%04d-%02d-%02dT%02d:%02d:%02dZ %s %s %.*s\n",
        utc.tm_year + 1900, utc.tm_mon + 1, utc.tm_mday,
        utc.tm_hour, utc.tm_min, utc.tm_sec, level, component,
        message_length, message);
    if (line_length <= 0)
        return;
    if (line_length > (int)sizeof(line))
        line_length = sizeof(line);
    off_t size = lseek(orbis_log_fd, 0, SEEK_END);
    if (size >= 0 && size + line_length > ORBis_LOG_LIMIT) {
        close(orbis_log_fd);
        orbis_log_fd = -1;
        if (rename(ORBis_LOG_PATH, ORBis_LOG_ROTATED_PATH) < 0)
            unlink(ORBis_LOG_PATH);
        orbis_log_fd = open(ORBis_LOG_PATH,
            O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (orbis_log_fd < 0)
            return;
    }
    write(orbis_log_fd, line, line_length);
}

static void orbis_log_close(void) {
    if (orbis_log_fd >= 0) {
        close(orbis_log_fd);
        orbis_log_fd = -1;
    }
}

#if ORBIS_PROFILE_SCREEN_CONSOLE
#define METASPACE_PROBE_SAMPLES 9
#define METASPACE_PROBE_INTERVAL_US 30000000

static int memory_probe_requested(void) {
    char path[512];
    int length = snprintf(path, sizeof(path), "%s/memory-probe.txt",
                          runner_config.working_directory);
    if (length < 0 || length >= (int)sizeof(path)) return -1;
    int fd = open(path, O_RDONLY);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    char marker[8] = {0};
    ssize_t bytes = read(fd, marker, sizeof(marker) - 1);
    int read_error = errno;
    close(fd);
    if (bytes < 0) {
        errno = read_error;
        return -1;
    }
    return strcmp(marker, "run") == 0 || strcmp(marker, "run\n") == 0 ? 1 : -1;
}

static int consume_metaspace_probe_request(void) {
    char path[512];
    int length = snprintf(path, sizeof(path), "%s/metaspace-probe.txt",
                          runner_config.working_directory);
    if (length < 0 || length >= (int)sizeof(path)) return -1;
    int fd = open(path, O_RDONLY);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    char marker[8] = {0};
    ssize_t bytes = read(fd, marker, sizeof(marker) - 1);
    int read_error = errno;
    close(fd);
    if (bytes < 0) {
        errno = read_error;
        return -1;
    }
    if (strcmp(marker, "run") != 0 && strcmp(marker, "run\n") != 0)
        return -1;
    if (unlink(path) < 0) return -1;
    return 1;
}

static void run_late_metaspace_probe(void) {
    const size_t request_size = 64u * 1024u * 1024u;
    const size_t alignment = 16u * 1024u * 1024u;
    static const size_t variant_sizes[] = {
        64u * 1024u * 1024u, 80u * 1024u * 1024u,
        32u * 1024u * 1024u, 48u * 1024u * 1024u,
        16u * 1024u * 1024u
    };
    static const size_t hint_sizes[] = {
        64u * 1024u * 1024u, 16u * 1024u * 1024u
    };
    static const uintptr_t address_hints[] = {
        (uintptr_t)0x210000000ULL, (uintptr_t)0x220000000ULL,
        (uintptr_t)0x240000000ULL, (uintptr_t)0x280000000ULL,
        (uintptr_t)0x300000000ULL
    };
    const int variant_flags = MAP_PRIVATE | MAP_ANON;
    struct timespec started;
    clock_gettime(CLOCK_MONOTONIC, &started);
    orbis_log_line("INFO", "metaspace-probe",
                   "begin samples=%d interval_seconds=30 size_mib=64 alignment_mib=16",
                   METASPACE_PROBE_SAMPLES);
    for (unsigned int i = 0; i < METASPACE_PROBE_SAMPLES; ++i) {
        if (i != 0)
            sceKernelUsleep(METASPACE_PROBE_INTERVAL_US);
        struct orbis_mapping_probe_result result;
        int status = orbis_mapping_probe_hotspot_reservation(
            request_size, alignment, &result);
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t elapsed = (int64_t)(now.tv_sec - started.tv_sec);
        if (now.tv_nsec < started.tv_nsec) --elapsed;
        orbis_log_line("INFO", "metaspace-probe",
                       "sample=%u elapsed_seconds=%lld status=%d raw_attempts=%u raw_errno=%d aligned_attempts=%u aligned_errno=%d address=0x%llx release_errno=%d",
                       i + 1, (long long)elapsed, status,
                       result.raw_attempts, result.raw_errno,
                       result.aligned_attempts, result.aligned_errno,
                       (unsigned long long)result.aligned_address,
                       result.release_errno);
        for (size_t size_index = 0;
             size_index < sizeof(variant_sizes) / sizeof(variant_sizes[0]);
             ++size_index) {
            static const int protections[] = {
                PROT_NONE, PROT_READ | PROT_WRITE
            };
            static const char *protection_names[] = {"none", "read-write"};
            for (size_t protection_index = 0;
                 protection_index < sizeof(protections) / sizeof(protections[0]);
                 ++protection_index) {
                struct orbis_mapping_variant_result variant;
                int variant_status = orbis_mapping_probe_variant(
                    variant_sizes[size_index], NULL,
                    protections[protection_index], variant_flags, &variant);
                orbis_log_line("INFO", "metaspace-probe",
                               "sample=%u mmap size_mib=%u protection=%s flags=0x%x status=%d address=0x%llx map_errno=%d release_errno=%d",
                               i + 1,
                               (unsigned int)(variant.requested_bytes / (1024u * 1024u)),
                               protection_names[protection_index], variant.flags,
                               variant_status,
                               (unsigned long long)variant.address,
                               variant.map_errno, variant.release_errno);
            }
        }
        for (size_t hint_index = 0;
             hint_index < sizeof(address_hints) / sizeof(address_hints[0]);
             ++hint_index) {
            for (size_t size_index = 0;
                 size_index < sizeof(hint_sizes) / sizeof(hint_sizes[0]);
                 ++size_index) {
                struct orbis_mapping_variant_result variant;
                void *hint = (void *)address_hints[hint_index];
                int hint_status = orbis_mapping_probe_variant(
                    hint_sizes[size_index], hint, PROT_NONE, variant_flags,
                    &variant);
                orbis_log_line("INFO", "metaspace-probe",
                               "sample=%u mmap-hint size_mib=%u requested_hint=0x%llx returned_address=0x%llx status=%d map_errno=%d release_errno=%d",
                               i + 1,
                               (unsigned int)(variant.requested_bytes / (1024u * 1024u)),
                               (unsigned long long)variant.requested_hint,
                               (unsigned long long)variant.address, hint_status,
                               variant.map_errno, variant.release_errno);
            }
        }
    }
    orbis_log_line("INFO", "metaspace-probe", "complete");
}

static int vm_map_trace_requested(void) {
    char path[512];
    int length = snprintf(path, sizeof(path), "%s/vmmap-probe.txt",
                          runner_config.working_directory);
    if (length < 0 || length >= (int)sizeof(path)) return -1;
    int fd = open(path, O_RDONLY);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    char marker[8] = {0};
    ssize_t bytes = read(fd, marker, sizeof(marker) - 1);
    int read_error = errno;
    close(fd);
    if (bytes < 0) {
        errno = read_error;
        return -1;
    }
    if (strcmp(marker, "run") == 0 || strcmp(marker, "run\n") == 0)
        return 1;
    return -1;
}

static int reset_vm_map_trace_log(void) {
    int fd = open("/data/orbis-jdk/logs/orbis-vmmap.log",
                  O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (fd < 0) return -1;
    return close(fd);
}

static void run_memory_mapping_comparison(const char *stage) {
    const size_t total = 128u * 1024u * 1024u;
    const size_t blocks[] = {total, 0x4000u};
    const char *names[] = {"single", "fragmented"};
    uint64_t available_bytes = 0;
    int available_status = orbis_available_flexible_memory(&available_bytes);
    orbis_log_line("INFO", "memprobe",
                   "%s flexible-memory status=%d bytes=%llu",
                   stage, available_status,
                   (unsigned long long)available_bytes);
    for (size_t i = 0; i < 2; ++i) {
        orbis_log_line("INFO", "memprobe", "%s %s begin total=%zu block=%zu",
                       stage, names[i], total, blocks[i]);
        struct orbis_mapping_probe_result result;
        int status = orbis_mapping_probe_run(total, blocks[i], &result);
        orbis_log_line("INFO", "memprobe",
                       "%s %s status=%d maps=%zu touched=%zu first_address=0x%llx map_errno=%d unmap_errno=%d",
                       stage, names[i], status, result.successful_maps,
                       result.touched_bytes,
                       (unsigned long long)result.first_mapping_address,
                       result.map_errno, result.unmap_errno);
    }
}

static void run_single_mapping_size_sweep(void) {
    static const size_t sizes_mib[] = {112, 113, 114, 115, 116, 117, 118, 119, 120};
    for (size_t i = 0; i < sizeof(sizes_mib) / sizeof(sizes_mib[0]); ++i) {
        size_t bytes = sizes_mib[i] * 1024u * 1024u;
        struct orbis_mapping_probe_result result;
        orbis_log_line("INFO", "memprobe",
                       "post-jvm single-size begin mib=%zu", sizes_mib[i]);
        int status = orbis_mapping_probe_run(bytes, bytes, &result);
        orbis_log_line("INFO", "memprobe",
                       "post-jvm single-size mib=%zu status=%d maps=%zu touched=%zu first_address=0x%llx map_errno=%d unmap_errno=%d",
                       sizes_mib[i], status, result.successful_maps,
                       result.touched_bytes,
                       (unsigned long long)result.first_mapping_address,
                       result.map_errno,
                       result.unmap_errno);
    }
}

static void run_hotspot_reservation_probe(const char *stage) {
    static const size_t sizes_mib[] = {64, 32, 16};
    const size_t alignment = 16u * 1024u * 1024u;
    for (size_t i = 0; i < sizeof(sizes_mib) / sizeof(sizes_mib[0]); ++i) {
        const size_t bytes = sizes_mib[i] * 1024u * 1024u;
        struct orbis_mapping_probe_result result;
        int status = orbis_mapping_probe_hotspot_reservation(
            bytes, alignment, &result);
        orbis_log_line("INFO", "memprobe",
                       "%s HotSpot-reserve size_mib=%zu alignment=%zu status=%d raw_attempts=%u raw_errno=%d aligned_attempts=%u aligned_errno=%d aligned_address=0x%llx release_errno=%d",
                       stage, sizes_mib[i], alignment, status,
                       result.raw_attempts, result.raw_errno,
                       result.aligned_attempts, result.aligned_errno,
                       (unsigned long long)result.aligned_address,
                       result.release_errno);
    }
}
#endif

static int runner_display_desktop(void) { return runner_config.display == RUNNER_DISPLAY_DESKTOP; }
static int runner_display_gpu(void) { return runner_config.display == RUNNER_DISPLAY_GPU; }
static const char *runner_display_name(void) {
    return runner_display_gpu() ? "gpu" : runner_display_desktop() ? "desktop" : "console";
}

// Desktop owns its toolkit, fonts and software pipeline. Console profiles keep
// their existing accepted option set.
static int desktop_option_conflict(const char *option) {
    if (!runner_display_desktop()) return 0;
    const char *prefixes[] = {"-Djava.awt.", "-Dawt.", "-Dorbis.awt.", "-Dcacio.",
        "-Dswing.", "-Dsun.awt.", "-Dsun.java2d.", "-Dsun.font.",
        "-Dsun.fontmanager", "-Dsun.fontconfig", "-Dsun.jnu.font",
        "-Djavax.accessibility."};
    for (size_t i = 0; i < sizeof(prefixes)/sizeof(prefixes[0]); ++i)
        if (!strncmp(option, prefixes[i], strlen(prefixes[i]))) return 1;
    return 0;
}

static int load_runtime_vm_options(JavaVMOption *options, char storage[][512],
                                  int capacity) {
    if (ORBIS_PROFILE_RUNTIME_JVM_OPTIONS[0] == '\0')
        return 0;
    FILE *file = fopen(ORBIS_PROFILE_RUNTIME_JVM_OPTIONS, "r");
    if (file == NULL) {
        if (errno == ENOENT) return 0;
        return -1;
    }
    int count = 0;
    char line[512];
    while (fgets(line, sizeof(line), file) != NULL) {
        size_t length = strlen(line);
        if (length == sizeof(line) - 1 && line[length - 1] != '\n' &&
            !feof(file)) {
            fclose(file);
            return -1;
        }
        while (length > 0 && (line[length - 1] == '\n' ||
                              line[length - 1] == '\r'))
            line[--length] = '\0';
        if (length == 0 || line[0] == '#') continue;
        if (count == capacity) {
            fclose(file);
            return -1;
        }
        if (runner_option_reserved(line) || desktop_option_conflict(line)) {
            orbis_log_line("ERROR", "launcher", "reserved JVM option %s; configure paths in %s", line, RUNNER_CONFIG_PATH);
            debug_text("orbis-java: reserved JVM option; configure paths in runner.conf\n");
            fclose(file);
            return -1;
        }
        memcpy(storage[count], line, length + 1);
        options[count].optionString = storage[count];
        options[count].extraInfo = NULL;
        ++count;
    }
    int failed = ferror(file);
    fclose(file);
    return failed ? -1 : count;
}

#if ORBIS_PROFILE_SCREEN_CONSOLE
static void JNICALL screen_console_write(JNIEnv *env, jclass klass,
                                        jbyteArray bytes, jint offset,
                                        jint length) {
    (void)klass;
    if (bytes == NULL || offset < 0 || length <= 0 ||
        offset + length > env->GetArrayLength(bytes))
        return;
    jbyte chunk[512];
    jint remaining = length;
    jint position = offset;
    while (remaining > 0) {
        jsize amount = remaining > (jint)sizeof(chunk) ?
                       (jsize)sizeof(chunk) : (jsize)remaining;
        env->GetByteArrayRegion(bytes, position, amount, chunk);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return;
        }
        orbis_screen_console_write(reinterpret_cast<const char *>(chunk), amount);
        position += amount;
        remaining -= amount;
    }
}

static void JNICALL screen_console_flush(JNIEnv *env, jclass klass) {
    (void)env;
    (void)klass;
    orbis_screen_console_flush();
}

static jint JNICALL screen_console_read_byte(JNIEnv *env, jclass klass) {
    (void)env;
    (void)klass;
    return (jint)orbis_screen_console_read_byte();
}

static int register_screen_console(JNIEnv *env) {
    jclass bridge = env->FindClass("runner/NativeConsoleOutputStream");
    if (bridge == NULL || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return -1;
    }
    JNINativeMethod methods[] = {
        {const_cast<char *>("screenWrite"), const_cast<char *>("([BII)V"),
         reinterpret_cast<void *>(screen_console_write)},
        {const_cast<char *>("screenFlush"), const_cast<char *>("()V"),
         reinterpret_cast<void *>(screen_console_flush)}
    };
    jint result = env->RegisterNatives(bridge, methods, 2);
    env->DeleteLocalRef(bridge);
    return result == JNI_OK ? 0 : -1;
}

static int register_screen_console_input(JNIEnv *env) {
    jclass bridge = env->FindClass("runner/NativeConsoleInputStream");
    if (bridge == NULL || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return -1;
    }
    JNINativeMethod methods[] = {
        {const_cast<char *>("readByte"), const_cast<char *>("()I"),
         reinterpret_cast<void *>(screen_console_read_byte)}
    };
    jint result = env->RegisterNatives(bridge, methods, 1);
    env->DeleteLocalRef(bridge);
    return result == JNI_OK ? 0 : -1;
}
#endif

static void park_after_error(void) {
    runner_debug("ENTER park_after_error (waiting indefinitely)");
    for (;;) {
        sceKernelUsleep(1000000);
    }
}

static int is_control_argument(const char *arg) {
    return arg && (strncmp(arg, "--main=", 7) == 0 ||
                   strcmp(arg, "--no-park") == 0 ||
                   strcmp(arg, "--allocator-probe") == 0 ||
                   strcmp(arg, "--fatal-allocation-probe") == 0);
}

static int parse_launcher_options(int argc, char **argv, char *main_class,
                                  size_t main_class_size, int *park,
                                  enum launcher_probe_mode *probe_mode) {
    const char *default_class = ORBIS_PROFILE_MAIN_CLASS;
    size_t default_length = strlen(default_class);
    if (default_length + 1 > main_class_size)
        return -1;
    memcpy(main_class, default_class, default_length + 1);
    *park = 1;
    *probe_mode = PROBE_MODE_JAVA;
    for (int i = 1; i < argc; ++i) {
        if (strncmp(argv[i], "--main=", 7) == 0) {
            const char *class_name = argv[i] + 7;
            size_t length = strlen(class_name);
            if (length == 0 || length + 1 > main_class_size)
                return -1;
            memcpy(main_class, class_name, length + 1);
            for (size_t j = 0; j < length; ++j)
                if (main_class[j] == '.') main_class[j] = '/';
        } else if (strcmp(argv[i], "--no-park") == 0) {
            *park = 0;
        } else if (strcmp(argv[i], "--allocator-probe") == 0) {
            *probe_mode = PROBE_MODE_ALLOCATOR;
        } else if (strcmp(argv[i], "--fatal-allocation-probe") == 0) {
            *probe_mode = PROBE_MODE_FATAL_ALLOCATION;
        }
    }
    return 0;
}

static void debug_text(const char *text) {
    runner_debug("UART %s", text);
    sceKernelDebugOutText(0, text);
}

static void debug_hex(const char *label, uint32_t value) {
    char digits[12] = "0x00000000\n";
    for (unsigned i = 0; i < 8; ++i)
        digits[2 + i] = "0123456789abcdef"[(value >> (28 - 4 * i)) & 15];
    debug_text(label);
    debug_text(digits);
}

static int run_allocator_probe(void) {
    struct orbis_alloc_result result;
    const size_t limit = 48U * 1024U * 1024U;
    int error = orbis_runtime_allocator_probe(64U * 1024U, limit, &result);
    orbis_log_line(error == 0 ? "INFO" : "ERROR", "allocator",
        "configured=%llu extended=%u allocated=%llu released=%llu error=%d",
        (unsigned long long)result.configured_heap_size,
        result.extended_allocation,
        (unsigned long long)result.allocated_bytes,
        (unsigned long long)result.released_bytes, error);
    if (error == 0 && result.allocated_bytes == limit &&
        result.released_bytes == limit) {
        debug_text("orbis-java: allocator probe PASS\n");
        orbis_log_line("INFO", "allocator", "PASS");
        return 0;
    }
    debug_text("orbis-java: allocator probe FAIL\n");
    orbis_log_line("ERROR", "allocator", "FAIL");
    return 1;
}

static void run_fatal_allocation_probe(void) {
    debug_text("orbis-java: fatal allocation probe primary marker\n");
    orbis_log_line("ERROR", "allocator", "fatal allocation primary marker");
    volatile size_t impossible = SIZE_MAX;
    void *allocation = malloc(impossible);
    if (allocation != NULL) {
        free(allocation);
        orbis_log_line("ERROR", "allocator", "impossible allocation succeeded");
    }
    orbis_log_close();
    orbis_runtime_exit(86);
}

static void probe_app_jar(void) {
    int fd = open("/app0/app.jar", O_RDONLY);
    debug_hex("orbis-java: app.jar open result: ", (uint32_t)fd);
    if (fd < 0)
        return;

    unsigned char header[4] = {0, 0, 0, 0};
    ssize_t count = read(fd, header, sizeof(header));
    close(fd);
    debug_hex("orbis-java: app.jar read result: ", (uint32_t)count);
    if (count == 4) {
        uint32_t signature = ((uint32_t)header[0] << 24) |
            ((uint32_t)header[1] << 16) |
            ((uint32_t)header[2] << 8) | (uint32_t)header[3];
        debug_hex("orbis-java: app.jar signature: ", signature);
    }
}

static void debug_java_string(const char *label, JNIEnv *env, jstring value) {
    if (value == NULL) {
        orbis_log_line("WARN", "launcher", "%s<null>", label);
        debug_text(label);
        debug_text("<null>\n");
        return;
    }
    const char *text = env->GetStringUTFChars(value, NULL);
    if (text == NULL) {
        orbis_log_line("WARN", "launcher", "%s<unavailable>", label);
        debug_text(label);
        debug_text("<unavailable>\n");
        return;
    }
    orbis_log_line("INFO", "launcher", "%s%s", label, text);
    debug_text(label);
    debug_text(text);
    debug_text("\n");
    env->ReleaseStringUTFChars(value, text);
}

static void debug_pending_exception(JNIEnv *env) {
    jthrowable exception = env->ExceptionOccurred();
    if (exception == NULL)
        return;

    env->ExceptionClear();
    jclass throwable_class = env->FindClass("java/lang/Throwable");
    if (throwable_class == NULL || env->ExceptionCheck()) {
        env->ExceptionClear();
        debug_text("orbis-java: exception text unavailable\n");
        return;
    }
    jmethodID to_string = env->GetMethodID(
        throwable_class, "toString", "()Ljava/lang/String;");
    jmethodID get_cause = env->GetMethodID(
        throwable_class, "getCause", "()Ljava/lang/Throwable;");
    if (to_string == NULL || get_cause == NULL || env->ExceptionCheck()) {
        env->ExceptionClear();
        debug_text("orbis-java: exception toString unavailable\n");
        return;
    }

    jthrowable current = exception;
    const char *labels[] = {
        "orbis-java: exception: ",
        "orbis-java: exception cause: ",
        "orbis-java: exception cause cause: ",
        "orbis-java: exception cause cause cause: "
    };
    for (unsigned depth = 0; current != NULL && depth < 4; ++depth) {
        jstring text = static_cast<jstring>(
            env->CallObjectMethod(current, to_string));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            debug_text("orbis-java: exception toString call failed\n");
            return;
        }
        debug_java_string(labels[depth], env, text);
        current = static_cast<jthrowable>(
            env->CallObjectMethod(current, get_cause));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            debug_text("orbis-java: exception getCause call failed\n");
            return;
        }
    }
}

static void debug_system_property(JNIEnv *env, const char *key) {
    jclass system_class = env->FindClass("java/lang/System");
    if (system_class == NULL || env->ExceptionCheck()) {
        env->ExceptionClear();
        debug_text("orbis-java: System class unavailable\n");
        return;
    }
    jmethodID get_property = env->GetStaticMethodID(
        system_class, "getProperty",
        "(Ljava/lang/String;)Ljava/lang/String;");
    if (get_property == NULL || env->ExceptionCheck()) {
        env->ExceptionClear();
        debug_text("orbis-java: System.getProperty unavailable\n");
        return;
    }
    jstring key_string = env->NewStringUTF(key);
    jstring value = static_cast<jstring>(env->CallStaticObjectMethod(
        system_class, get_property, key_string));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        debug_text("orbis-java: System.getProperty failed\n");
        return;
    }
    debug_java_string("orbis-java: property: ", env, value);
}

static int check_exception(JNIEnv *env) {
    if (!env->ExceptionCheck())
        return 0;
    debug_text("orbis-java: Java exception pending\n");
    orbis_log_line("ERROR", "launcher", "Java exception pending");
    debug_pending_exception(env);
    env->ExceptionDescribe();
    return 1;
}

static void startup_error(const char *path) {
    debug_text("orbis-java: startup failed: ");
    debug_text(path);
    debug_text("\n");
    orbis_log_line("ERROR", "launcher", "startup failed path/detail=%s errno=%d", path, errno);
#if ORBIS_PROFILE_SCREEN_CONSOLE
    orbis_screen_console_write("Startup failed: ", 16);
    orbis_screen_console_write(path, strlen(path));
    orbis_screen_console_write("\n", 1);
    orbis_screen_console_flush();
#endif
}

static int require_readable_file(const char *path) {
    OrbisRunnerStat status;
    const char *operation = NULL;
    int saved_errno = 0;
    if (stat(path, &status) < 0) {
        operation = "stat";
        saved_errno = errno;
    } else if (!S_ISREG(status.mode)) {
        operation = "regular-file check";
        saved_errno = EINVAL;
    } else {
        // Check the operation needed by the loader. The console can reject
        // access(R_OK) even when the file exists; opening also verifies access.
        int fd = open(path, O_RDONLY);
        if (fd < 0) {
            operation = "open(O_RDONLY)";
            saved_errno = errno;
        } else if (close(fd) < 0) {
            operation = "close(read-only file)";
            saved_errno = errno;
        }
    }
    if (operation != NULL) {
        char detail[1024];
        snprintf(detail, sizeof(detail), "file check failed: %s errno=%d path=%s\n",
                 operation, saved_errno, path);
        debug_text(detail);
        orbis_log_line("ERROR", "launcher", "%s", detail);
        errno = saved_errno;
        startup_error(path);
        return -1;
    }
    return 0;
}

static int require_writable_directory(const char *path) {
    char probe[1024];
    snprintf(probe, sizeof(probe), "%s/.runner-write-check-%ld", path, (long)getpid());
    int fd = open(probe, O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (fd < 0) { startup_error(path); return -1; }
    int result = close(fd);
    if (unlink(probe) < 0) result = -1;
    if (result < 0) startup_error(path);
    return result;
}

static int validate_runner_paths(void) {
    char modules[1024], names[1024], parent[512];
    snprintf(modules, sizeof(modules), "%s/lib/modules", runner_config.java_home);
    snprintf(names, sizeof(names), "%s/lib/orbis-modules.list", runner_config.java_home);
    snprintf(runner_jvm_path, sizeof(runner_jvm_path), "%s/lib/server/libjvm.prx", runner_config.java_home);
    if (require_readable_file(runner_config.jar) < 0 || require_readable_file(modules) < 0 ||
        require_readable_file(names) < 0 || require_readable_file(runner_jvm_path) < 0) return -1;
    if (require_writable_directory(runner_config.working_directory) < 0 ||
        require_writable_directory(runner_config.temporary_directory) < 0) return -1;
    strcpy(parent, runner_config.output_log);
    char *slash = strrchr(parent, '/');
    if (slash == parent) slash[1] = 0; else *slash = 0;
    if (require_writable_directory(parent) < 0) return -1;
    int fd = open(runner_config.output_log, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd < 0) { startup_error(runner_config.output_log); return -1; }
    if (close(fd) < 0) { startup_error(runner_config.output_log); return -1; }
    return 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    orbis_log_open();
    char configuration_error[1024];
    int configuration_result = runner_config_load(RUNNER_CONFIG_PATH, &runner_config,
        configuration_error, sizeof(configuration_error));
#if ORBIS_PROFILE_SCREEN_CONSOLE
    // GPU mode leaves VideoOut and input to the graphics bundle. Console, desktop
    // and configuration failures keep the CPU screen for startup diagnostics.
    int screen_console_ready = 0;
    if (configuration_result < 0 || !runner_display_gpu()) {
        screen_console_ready = orbis_screen_console_init() == 0;
        if (!screen_console_ready) {
            debug_text("orbis-java: screen console unavailable; continuing\n");
            orbis_log_line("WARN", "launcher", "screen console initialization failed");
        }
    } else {
        debug_text("orbis-java: GPU display mode; CPU screen console not started\n");
        orbis_log_line("INFO", "launcher", "GPU display mode; CPU display/input not started");
    }
    ScreenConsoleLifetime screen_console_lifetime;
#endif
    if (configuration_result < 0) {
        startup_error(configuration_error);
        return 3;
    }
    if (runner_config.debug) {
        // Enable after successful parsing, before the first configured path check.
        if (mkdir_path(RUNNER_ROOT) < 0) { startup_error(RUNNER_ROOT); return 3; }
        if (rename(RUNNER_DEBUG_LOG, RUNNER_ROOT "/runner-debug.log.1") < 0 && errno != ENOENT) {
            startup_error("cannot preserve previous runner-debug.log"); return 3;
        }
        int debug_fd = open(RUNNER_DEBUG_LOG, O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (debug_fd < 0) { startup_error(RUNNER_DEBUG_LOG); return 3; }
        close(debug_fd);
        runner_debug_ready = 1;
        runner_debug("SESSION start build=%s; configuration load completed path=%s", ORBIS_JAVA_BUILD_VERSION, RUNNER_CONFIG_PATH);
        runner_debug("CONFIG java_home=%s jar=%s working_directory=%s output_log=%s temporary_directory=%s debug=true display=%s desktop_bundle=%s graphics_bundle=%s version_metadata=%s libraries=%s",
            runner_config.java_home, runner_config.jar, runner_config.working_directory,
            runner_config.output_log, runner_config.temporary_directory, runner_display_name(),
            runner_config.desktop_bundle, runner_config.graphics_bundle,
            runner_config.version_metadata, runner_config.libraries);
        runner_debug("EARLY startup log opened; configuration loaded before display/input ownership was selected");
    }
    runner_debug("ENTER prepare directories");
    if (prepare_profile_directories() < 0) {
        orbis_log_close();
        return 3;
    }
    runner_debug("RETURN prepare directories success; ENTER validate paths");
    if (validate_runner_paths() < 0) return 3;
    runner_debug("RETURN validate paths success");
    if (chdir(runner_config.working_directory) < 0) {
        int cwd_error = errno;
        if (cwd_error != EPERM && cwd_error != ENOSYS) {
            startup_error(runner_config.working_directory);
            return 3;
        }
        orbis_log_line("WARN", "launcher",
                       "native chdir unavailable errno=%d; using emulated Java working directory=%s",
                       cwd_error, runner_config.working_directory);
        runner_debug("BOUNDARY native cwd unavailable; continue with user.dir and per-PRX ORBIS_JAVA_USER_DIR");
    }
    char main_class_name[256];
    int park = 1;
    enum launcher_probe_mode probe_mode;
    runner_debug("ENTER parse launcher options argc=%d", argc);
    if (parse_launcher_options(argc, argv, main_class_name,
                               sizeof(main_class_name), &park,
                               &probe_mode) < 0) {
        debug_text("orbis-java: invalid launcher options\n");
        return 2;
    }
    if (runner_display_gpu()) {
        if (strcmp(main_class_name, "runner/Main")) {
            startup_error("gpu display requires runner/Main orchestration");
            return 3;
        }
        char manifest[RUNNER_PATH_CAPACITY + 32];
        snprintf(manifest, sizeof(manifest), "%s/graphics-bundle.json", runner_config.graphics_bundle);
        if (require_readable_file(manifest) < 0) return 3;
        orbis_log_line("INFO", "launcher", "graphics bundle=%s", runner_config.graphics_bundle);
    }
    if (runner_display_desktop()) {
        if (strcmp(main_class_name, "runner/Main")) {
            startup_error("desktop requires runner/Main orchestration");
            return 3;
        }
        char desktop_error[1024];
        if (orbis_desktop_preflight(&runner_config, desktop_error, sizeof(desktop_error)) < 0) {
            startup_error(desktop_error);
            return 3;
        }
    }
    runner_debug("RETURN parse launcher options main=%s", main_class_name);
    if (setenv("ORBIS_JAVA_USER_DIR", runner_config.working_directory, 1) < 0) {
        debug_text("orbis-java: cannot set emulated working directory\n");
        orbis_log_line("ERROR", "launcher", "setenv working directory failed errno=%d",
                       errno);
        startup_error(runner_config.working_directory);
        return 3;
    } else {
        orbis_log_line("INFO", "launcher", "emulated working directory=%s",
                       runner_config.working_directory);
    }
    if (setenv("ORBIS_JAVA_TMP_DIR", runner_config.temporary_directory, 1) < 0) {
        debug_text("orbis-java: cannot set native temporary directory\n");
        orbis_log_line("ERROR", "launcher",
                       "setenv native temporary directory failed errno=%d",
                       errno);
        startup_error(runner_config.temporary_directory);
        return 3;
    } else if (setenv("TMPDIR", runner_config.temporary_directory, 1) < 0) {
        debug_text("orbis-java: cannot set TMPDIR\n");
        orbis_log_line("ERROR", "launcher", "setenv TMPDIR failed errno=%d",
                       errno);
        startup_error(runner_config.temporary_directory);
        return 3;
    } else {
        orbis_log_line("INFO", "launcher", "native temporary directory=%s",
                       runner_config.temporary_directory);
    }
    orbis_log_line("INFO", "launcher",
                   "argc=%d effective-main=%s park=%d",
                   argc, main_class_name, park);
    for (int i = 0; i < argc; ++i) {
        if (i == 0) {
            orbis_log_line("INFO", "launcher",
                           "argument[0]=<argv0>");
        } else if (strcmp(argv[i], "--no-park") == 0) {
            orbis_log_line("INFO", "launcher",
                           "argument[%d]=--no-park", i);
        } else if (strcmp(argv[i], "--allocator-probe") == 0) {
            orbis_log_line("INFO", "launcher",
                           "argument[%d]=--allocator-probe", i);
        } else if (strcmp(argv[i], "--fatal-allocation-probe") == 0) {
            orbis_log_line("INFO", "launcher",
                           "argument[%d]=--fatal-allocation-probe", i);
        } else if (strncmp(argv[i], "--main=", 7) == 0) {
            orbis_log_line("INFO", "launcher",
                           "argument[%d]=--main=<effective-main-recorded>", i);
        } else {
            orbis_log_line("INFO", "launcher",
                           "argument[%d]=<application-argument>", i);
        }
    }
    orbis_log_line("INFO", "launcher", "loader build %s",
                   ORBIS_JAVA_BUILD_VERSION);
#if ORBIS_PROFILE_SCREEN_CONSOLE
    if (screen_console_ready) {
        const char *banner = "Orbis Java JAR runner\nStarting Java 17...\n";
        orbis_screen_console_write(banner, strlen(banner));
        debug_text("orbis-java: screen console initialized\n");
    }
#endif
    debug_text("orbis-java: loader build ");
    debug_text(ORBIS_JAVA_BUILD_VERSION);
    debug_text(" (");
    debug_text(ORBIS_JAVA_BUILD_KIND);
    debug_text(")\n");
    debug_text("orbis-java: entered main\n");
    orbis_log_line("INFO", "launcher", "entered main");
#if ORBIS_PROFILE_SCREEN_CONSOLE
    int vm_map_trace = vm_map_trace_requested();
    if (vm_map_trace < 0) {
        orbis_log_line("ERROR", "vmmap", "invalid or unreadable vmmap-probe.txt");
        orbis_log_close();
        return 1;
    }
    if (vm_map_trace > 0) {
        if (reset_vm_map_trace_log() < 0) {
            orbis_log_line("ERROR", "vmmap", "cannot reset trace log errno=%d", errno);
            orbis_log_close();
            return 1;
        }
        orbis_log_line("INFO", "vmmap", "one-shot HotSpot mapping trace armed");
    }
    int memory_probe = memory_probe_requested();
    if (memory_probe < 0) {
        orbis_log_line("ERROR", "memprobe", "invalid or unreadable memory-probe.txt");
        orbis_log_close();
        return 1;
    }
    if (memory_probe > 0)
        run_memory_mapping_comparison("pre-jvm");
    int late_metaspace_probe = consume_metaspace_probe_request();
    if (late_metaspace_probe < 0) {
        orbis_log_line("ERROR", "metaspace-probe",
                       "invalid, unreadable, or unconsumable metaspace-probe.txt");
        orbis_log_close();
        return 1;
    }
    if (late_metaspace_probe > 0)
        orbis_log_line("INFO", "metaspace-probe",
                       "one-shot late mapping sampler armed");
#endif
    if (probe_mode == PROBE_MODE_ALLOCATOR) {
        int probe_result = run_allocator_probe();
        orbis_log_close();
        if (park) park_after_error();
        return probe_result;
    }
    if (probe_mode == PROBE_MODE_FATAL_ALLOCATION)
        run_fatal_allocation_probe();
    probe_app_jar();
    debug_text("orbis-java: before sceKernelLoadStartModule\n");
    int start_result = 0;
    int32_t jvm_handle = (int32_t)sceKernelLoadStartModule(
        runner_jvm_path, 0, NULL, 0, NULL, &start_result);
    debug_hex("orbis-java: native load result: ", (uint32_t)jvm_handle);
    debug_hex("orbis-java: module start result: ", (uint32_t)start_result);
    orbis_log_line("INFO", "launcher", "JVM load=%d start=%d",
                   jvm_handle, start_result);
    if (jvm_handle < 0) {
        debug_text("orbis-java: native loader rejected JVM module; VM not started\n");
        park_after_error();
    }

    if (runner_config.debug) {
        void *debug_entry = NULL;
        runner_debug("ENTER dlsym orbis_set_debug_log");
        int debug_result = sceKernelDlsym(jvm_handle, "orbis_set_debug_log", &debug_entry);
        runner_debug("RETURN dlsym orbis_set_debug_log result=%d address=%p", debug_result, debug_entry);
        if (debug_result < 0 || !debug_entry) {
            startup_error("debug mode requires updated JDK export (orbis_set_debug_log)"); return 3;
        }
        runner_debug("ENTER configure native debug log");
        int configured = reinterpret_cast<int (*)(const char *)>(debug_entry)(RUNNER_DEBUG_LOG);
        runner_debug("RETURN configure native debug log result=%d", configured);
        if (configured < 0) { startup_error("native debug log configuration failed"); return 3; }
    }
    runner_debug("ENTER JVM runtime configuration");
    void *configure_entry = NULL;
    if (sceKernelDlsym(jvm_handle, "orbis_configure_runtime_v2", &configure_entry) < 0 ||
        !configure_entry ||
        reinterpret_cast<int (*)(const char *, const char *, const char *)>(configure_entry)(
            runner_config.java_home, runner_config.working_directory,
            runner_config.temporary_directory) < 0) {
        orbis_log_line("ERROR", "launcher", "Cannot configure JVM runtime: %s",
                       runner_config.java_home);
        debug_text("orbis-java: JVM runtime configuration failed\n");
        park_after_error();
    }

    runner_debug("RETURN JVM runtime configuration success");
    debug_text("orbis-java: before JVM initializers\n");
    void *initializer_entry = NULL;
    int32_t initializer_symbol_result = sceKernelDlsym(
        jvm_handle, "orbis_run_initializers", &initializer_entry);
    debug_hex("orbis-java: initializer symbol result: ",
              (uint32_t)initializer_symbol_result);
    if (initializer_symbol_result < 0 || initializer_entry == NULL) {
        debug_text("orbis-java: JVM initializer lookup failed\n");
        park_after_error();
    }
    int initializer_count =
        reinterpret_cast<run_initializers_fn>(initializer_entry)();
    debug_hex("orbis-java: initializer count: ", (uint32_t)initializer_count);
    if (initializer_count < 0) {
        debug_text("orbis-java: JVM initializer recursion detected\n");
        park_after_error();
    }

    debug_text("orbis-java: before native symbol lookup\n");
    void *entry = NULL;
    int32_t symbol_result = sceKernelDlsym(jvm_handle, "JNI_CreateJavaVM", &entry);
    debug_hex("orbis-java: native symbol result: ", (uint32_t)symbol_result);
    create_vm_fn create_vm = symbol_result < 0 ? NULL :
        reinterpret_cast<create_vm_fn>(entry);
    debug_text("orbis-java: after native symbol lookup\n");
    if (create_vm == NULL) {
        debug_text("orbis-java: JNI_CreateJavaVM lookup failed\n");
        park_after_error();
    }

    constexpr int runtime_option_capacity = 32;
    constexpr int configured_option_count = 18;
    constexpr int desktop_option_capacity = 32;
    int desktop_option_count = runner_display_desktop() ? static_cast<int>(runner_desktop_option_count()) : 0;
    if (desktop_option_count > desktop_option_capacity) { startup_error("desktop option capacity exceeded"); return 3; }
    char configured_storage[configured_option_count][2048];
    char runtime_option_storage[runtime_option_capacity][512];
    JavaVMOption options[ORBIS_PROFILE_VM_OPTION_COUNT + runtime_option_capacity + configured_option_count + desktop_option_capacity];
    for (int i = 0; i < ORBIS_PROFILE_VM_OPTION_COUNT; ++i)
        {
        if (runner_option_reserved(orbis_profile_vm_options[i]) || desktop_option_conflict(orbis_profile_vm_options[i])) {
            startup_error("reserved JVM property in launcher profile");
            return 3;
        }
        options[i].optionString = const_cast<char *>(orbis_profile_vm_options[i]);
        options[i].extraInfo = NULL;
    }

    const char *configured_formats[] = {
        "-Djava.home=%s", "-Djava.class.path=%s",
        "-Djava.library.path=%s",
        "-Djava.io.tmpdir=%s", "-Duser.dir=%s", "-Drunner.jar=%s",
        "-Drunner.output.log=%s", "-Dorbis.runner.root=" RUNNER_ROOT,
        "-Dorbis.module.names.file=%s/lib/orbis-modules.list",
        "-Dorbis.module.finder.imageFallback=true",
        "-Dorbis.runner.screen.enabled=%s", "-Drunner.debug.log=%s",
        "-Drunner.display=%s", "-Drunner.desktop.bundle=%s",
        "-Djava.system.class.loader=%s", "-Drunner.graphics.bundle=%s",
        "-Drunner.gpu.metadata=%s", "-Drunner.gpu.libraries=%s"
    };
    // GPU mode appends the graphics bundle's natives after the JDK search path.
    char library_path[2048];
    int library_length = snprintf(library_path, sizeof(library_path),
        "%s/lib:%s/lib/server:/app0/sce_module%s%s%s", runner_config.java_home,
        runner_config.java_home, runner_display_gpu() ? ":" : "",
        runner_display_gpu() ? runner_config.graphics_bundle : "",
        runner_display_gpu() ? "/lib" : "");
    if (library_length < 0 || library_length >= static_cast<int>(sizeof(library_path))) {
        startup_error("library path exceeds capacity"); return 3;
    }
    char system_classpath[2048] = "/app0/app.jar";
    if (runner_display_desktop()) {
        size_t used = strlen(system_classpath);
        for (size_t i = 0; i < runner_desktop_jar_count(); ++i) {
            int length = snprintf(system_classpath + used, sizeof(system_classpath) - used,
                ":%s/lib/%s", runner_config.desktop_bundle, runner_desktop_jar_at(i));
            if (length < 0 || static_cast<size_t>(length) >= sizeof(system_classpath) - used) {
                startup_error("desktop classpath exceeds capacity"); return 3;
            }
            used += length;
        }
    }
    const char *configured_values[] = { runner_config.java_home, system_classpath, library_path,
        runner_config.temporary_directory, runner_config.working_directory,
        runner_config.jar, runner_config.output_log, "", runner_config.java_home, "",
        ORBIS_PROFILE_SCREEN_CONSOLE && !runner_display_gpu() ? "true" : "false",
        runner_config.debug ? RUNNER_DEBUG_LOG : "",
        runner_display_name(), runner_config.desktop_bundle,
        "runner.RunnerSystemClassLoader", runner_config.graphics_bundle,
        runner_config.version_metadata, runner_config.libraries };
    for (int i = 0; i < configured_option_count; ++i) {
        int length = snprintf(configured_storage[i], sizeof(configured_storage[i]), configured_formats[i],
                 configured_values[i], configured_values[i]);
        if (length < 0 || length >= static_cast<int>(sizeof(configured_storage[i]))) {
            startup_error("configured JVM option exceeds capacity"); return 3;
        }
        options[ORBIS_PROFILE_VM_OPTION_COUNT + i].optionString = configured_storage[i];
        options[ORBIS_PROFILE_VM_OPTION_COUNT + i].extraInfo = NULL;
    }
    runner_debug("ENTER read JVM options file");
    int runtime_option_count = load_runtime_vm_options(
        options + ORBIS_PROFILE_VM_OPTION_COUNT + configured_option_count, runtime_option_storage,
        runtime_option_capacity);
    runner_debug("RETURN read JVM options file result=%d", runtime_option_count);
    if (runtime_option_count < 0) {
        orbis_log_line("ERROR", "launcher", "invalid JVM options file");
        debug_text("orbis-java: JVM options file invalid or too large\n");
        park_after_error();
    }

    startup_dump_after_seconds = startup_dump_delay();
    if (startup_dump_after_seconds < 0) {
        orbis_log_line("WARN", "startup-watchdog",
                       "invalid startup-dump-after-seconds.txt; diagnostic disabled");
        startup_dump_after_seconds = 0;
    }
    if (startup_dump_after_seconds > 0) {
        struct sigaction previous;
        if (sigaction(SIGQUIT, NULL, &previous) < 0) {
            orbis_log_line("WARN", "startup-watchdog",
                           "cannot inspect SIGQUIT handler; diagnostic disabled errno=%d",
                           errno);
            startup_dump_after_seconds = 0;
        } else {
            startup_previous_sigquit_handler = previous.sa_handler;
        }
    }
    if (startup_dump_after_seconds > 0) {
        if (runtime_option_count > runtime_option_capacity - 4) {
            orbis_log_line("WARN", "startup-watchdog",
                           "JVM options leave no room for diagnostic options");
            startup_dump_after_seconds = 0;
        } else {
            static const char *diagnostic_options[] = {
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+LogVMOutput",
                "-XX:-DisplayVMOutput"
            };
            for (size_t i = 0; i < sizeof(diagnostic_options) /
                                    sizeof(diagnostic_options[0]); ++i) {
                strcpy(runtime_option_storage[runtime_option_count],
                       diagnostic_options[i]);
                options[ORBIS_PROFILE_VM_OPTION_COUNT + configured_option_count + runtime_option_count].optionString =
                    runtime_option_storage[runtime_option_count];
                options[ORBIS_PROFILE_VM_OPTION_COUNT + configured_option_count + runtime_option_count].extraInfo = NULL;
                ++runtime_option_count;
            }
            int length = snprintf(runtime_option_storage[runtime_option_count],
                                  sizeof(runtime_option_storage[0]),
                                  "-XX:LogFile=/data/orbis-jdk/logs/orbis-startup-vm.log");
            if (length < 0 || length >= (int)sizeof(runtime_option_storage[0])) {
                startup_dump_after_seconds = 0;
                orbis_log_line("WARN", "startup-watchdog",
                               "cannot construct VM output log option; diagnostic disabled");
            } else {
                options[ORBIS_PROFILE_VM_OPTION_COUNT + configured_option_count + runtime_option_count].optionString =
                    runtime_option_storage[runtime_option_count];
                options[ORBIS_PROFILE_VM_OPTION_COUNT + configured_option_count + runtime_option_count].extraInfo = NULL;
                ++runtime_option_count;
                orbis_log_line("INFO", "startup-watchdog",
                               "armed timeout=%d seconds vm-log=/data/orbis-jdk/logs/orbis-startup-vm.log",
                               startup_dump_after_seconds);
            }
        }
    }
    orbis_log_line("INFO", "launcher", "runtime JVM options=%d",
                   runtime_option_count);

    for (int i = 0; i < desktop_option_count; ++i) {
        int index = ORBIS_PROFILE_VM_OPTION_COUNT + configured_option_count + runtime_option_count + i;
        options[index].optionString = const_cast<char *>(runner_desktop_option_at(i));
        options[index].extraInfo = NULL;
    }
    JavaVMInitArgs vm_args;
    vm_args.version = JNI_VERSION_1_8;
    vm_args.nOptions = ORBIS_PROFILE_VM_OPTION_COUNT + configured_option_count + runtime_option_count + desktop_option_count;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = JNI_FALSE;

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    debug_text("orbis-java: before JNI_CreateJavaVM\n");
    orbis_log_line("INFO", "launcher", "before JNI_CreateJavaVM");
    pthread_t startup_watchdog_thread;
    int startup_watchdog_started = 0;
    if (startup_dump_after_seconds > 0) {
        set_startup_vm_pending(1);
        int thread_result = pthread_create(&startup_watchdog_thread, NULL,
                                           startup_dump_watchdog, NULL);
        if (thread_result == 0) {
            startup_watchdog_started = 1;
        } else {
            set_startup_vm_pending(0);
            orbis_log_line("WARN", "startup-watchdog",
                           "pthread_create failed result=%d; no dump will be requested",
                           thread_result);
        }
    }
    for (int i = 0; i < vm_args.nOptions; ++i) runner_debug("JVM option[%d]=%s", i, options[i].optionString);
    jint result = create_vm(&vm, (void **)&env, &vm_args);
    set_startup_vm_pending(0);
    if (startup_watchdog_started)
        pthread_join(startup_watchdog_thread, NULL);
    debug_text("orbis-java: after JNI_CreateJavaVM\n");
    orbis_log_line("INFO", "launcher", "after JNI_CreateJavaVM result=%d", result);
    if (result != JNI_OK) {
        debug_hex("orbis-java: JNI_CreateJavaVM result: ", (uint32_t)result);
        debug_text("orbis-java: JNI_CreateJavaVM failed\n");
        park_after_error();
    }

#if ORBIS_PROFILE_SCREEN_CONSOLE
    if (memory_probe > 0) {
        run_memory_mapping_comparison("post-jvm");
        run_single_mapping_size_sweep();
        run_hotspot_reservation_probe("post-jvm");
        orbis_log_line("INFO", "memprobe", "before DestroyJavaVM");
        vm->DestroyJavaVM();
        orbis_log_line("INFO", "memprobe", "complete");
        orbis_log_close();
        return 0;
    }
#endif

#if ORBIS_PROFILE_SCREEN_CONSOLE
    runner_debug("ENTER register screen output JNI bridge");
    if (register_screen_console(env) < 0) {
        debug_text("orbis-java: screen console Java bridge registration failed\n");
        orbis_log_line("ERROR", "launcher", "screen console bridge registration failed");
        vm->DestroyJavaVM();
        park_after_error();
    }
    runner_debug("RETURN register screen output success; ENTER register screen input JNI bridge");
    if (register_screen_console_input(env) < 0) {
        debug_text("orbis-java: screen console input bridge registration failed\n");
        orbis_log_line("ERROR", "launcher", "screen console input bridge registration failed");
        vm->DestroyJavaVM();
        park_after_error();
    }
    runner_debug("RETURN register screen input success");
#endif

    if (runner_config.debug) orbis_input_set_diagnostics(1);
    if (runner_display_gpu() && orbis_gpu_input_bridge_register(env) < 0) {
        check_exception(env);
        startup_error("gpu input JNI bridge registration failed");
        vm->DestroyJavaVM();
        return 3;
    }
    if (runner_display_desktop() && orbis_desktop_bridge_register(env) < 0) {
        check_exception(env);
        startup_error("desktop JNI bridge registration failed");
        vm->DestroyJavaVM();
        return 3;
    }
    debug_system_property(env, "java.home");
    debug_system_property(env, "java.class.path");

    debug_text("orbis-java: before FindClass main class\n");
    jclass main_class = env->FindClass(main_class_name);
    debug_text("orbis-java: after FindClass main class\n");
    orbis_log_line("INFO", "launcher", "main class=%s", main_class_name);
    int main_class_exception = check_exception(env);
    if (main_class == NULL || main_class_exception) {
        debug_text("orbis-java: FindClass runner bootstrap failed\n");
        vm->DestroyJavaVM();
        return 1;
    }

    debug_text("orbis-java: before GetStaticMethodID main\n");
    jmethodID main_method = env->GetStaticMethodID(
        main_class, "main", "([Ljava/lang/String;)V");
    debug_text("orbis-java: after GetStaticMethodID main\n");
    int main_method_exception = check_exception(env);
    if (main_method == NULL || main_method_exception) {
        debug_text("orbis-java: GetStaticMethodID main failed\n");
        vm->DestroyJavaVM();
        return 1;
    }

    debug_text("orbis-java: before FindClass String\n");
    jclass string_class = env->FindClass("java/lang/String");
    debug_text("orbis-java: after FindClass String\n");
    int string_class_exception = check_exception(env);
    if (string_class == NULL || string_class_exception) {
        debug_text("orbis-java: FindClass String failed\n");
        vm->DestroyJavaVM();
        return 1;
    }
    debug_text("orbis-java: before NewObjectArray\n");
    int java_argc = 0;
    for (int i = 1; i < argc; ++i)
        if (!is_control_argument(argv[i])) ++java_argc;
    jobjectArray java_args = env->NewObjectArray(java_argc, string_class, NULL);
    debug_text("orbis-java: after NewObjectArray\n");

    int java_arg_index = 0;
    for (int i = 1; i < argc; i++) {
        if (is_control_argument(argv[i])) continue;
        runner_debug("ENTER JNI NewStringUTF argument=%d", i);
        jstring value = env->NewStringUTF(argv[i]);
        runner_debug("RETURN JNI NewStringUTF address=%p; ENTER SetObjectArrayElement index=%d", (void *)value, java_arg_index);
        env->SetObjectArrayElement(java_args, java_arg_index++, value);
        runner_debug("RETURN JNI SetObjectArrayElement");
    }

    debug_text("orbis-java: before CallStaticVoidMethod main\n");
    orbis_log_line("INFO", "launcher", "before Java main invocation");
    env->CallStaticVoidMethod(main_class, main_method, java_args);
    debug_text("orbis-java: after CallStaticVoidMethod main\n");
    orbis_log_line("INFO", "launcher", "after Java main invocation");
    int failed = check_exception(env);
    orbis_log_line(failed ? "ERROR" : "INFO", "launcher",
                   "Java main returned failed=%d", failed);

#if ORBIS_PROFILE_SCREEN_CONSOLE
    if (late_metaspace_probe > 0 && !failed)
        run_late_metaspace_probe();
    else if (late_metaspace_probe > 0)
        orbis_log_line("WARN", "metaspace-probe",
                       "skipped because Java main returned an exception");
#endif

    debug_text("orbis-java: before DestroyJavaVM\n");
    orbis_log_line("INFO", "launcher", "before DestroyJavaVM");
    vm->DestroyJavaVM();
    debug_text("orbis-java: after DestroyJavaVM\n");
    orbis_log_line("INFO", "launcher", "after DestroyJavaVM");
    printf("orbis-java: Java main completed (failed=%d)\n", failed);
    orbis_log_line("INFO", "launcher", "Java main completed failed=%d", failed);
    orbis_log_close();
    if (park)
        park_after_error();
    return failed ? 1 : 0;
}
