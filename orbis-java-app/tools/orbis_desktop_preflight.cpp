#include "orbis_desktop_preflight.h"
#include "orbis_stat.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int fail(char *error, size_t size, const char *message) {
    if (size) snprintf(error, size, "%s", message);
    return -1;
}
static int fail_errno(char *error, size_t size, const char *operation,
                      const char *path, int error_number) {
    if (size) snprintf(error, size, "%s failed for %s (errno=%d: %s)",
                       operation, path, error_number, strerror(error_number));
    return -1;
}
static int path_has_symlink(const char *path, char *error, size_t size) {
    char prefix[RUNNER_PATH_CAPACITY * 2];
    size_t length = strlen(path);
    if (length >= sizeof(prefix)) {
        if (size) snprintf(error, size, "desktop artifact path is too long: %s", path);
        return 1;
    }
    memcpy(prefix, path, length + 1);
    if (prefix[0] != '/') {
        if (size) snprintf(error, size, "desktop artifact path is not absolute: %s", path);
        return 1;
    }
    for (char *slash = prefix + 1; ; ++slash) {
        if (*slash != '/' && *slash != 0) continue;
        char saved = *slash;
        *slash = 0;
        if (prefix[1] != 0) {
            OrbisRunnerStat attributes;
            /* Orbis libc's lstat routes through unsupported fstatat. stat is
             * supported here; on this runtime it follows links, so the mode
             * check only rejects S_IFLNK if the platform reports it. */
            if (stat(prefix, (struct stat *)(void *)&attributes) != 0) {
                int saved_errno = errno;
                fail_errno(error, size, "stat component", prefix, saved_errno);
                return 1;
            }
            if ((attributes.mode & S_IFMT) == S_IFLNK) {
                if (size) snprintf(error, size, "symlinked desktop artifact component: %s", prefix);
                return 1;
            }
        }
        *slash = saved;
        if (!saved) break;
    }
    return 0;
}
static int open_regular(const char *path, char *error, size_t size) {
    if (path_has_symlink(path, error, size)) return -1;
    int fd = open(path, O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        int saved_errno = errno;
        fail_errno(error, size, "open", path, saved_errno);
        return -1;
    }
    OrbisRunnerStat attributes;
    if (fstat(fd, (struct stat *)(void *)&attributes) != 0) {
        int saved_errno = errno;
        close(fd);
        fail_errno(error, size, "fstat", path, saved_errno);
        return -1;
    }
    if ((attributes.mode & S_IFMT) != S_IFREG) {
        if (size) snprintf(error, size, "unexpected file mode for %s (mode=0%o)",
                           path, (unsigned)attributes.mode);
        close(fd);
        return -1;
    }
    return fd;
}
static int bounded_file(const char *path, size_t maximum, char *contents,
                        char *error, size_t size, const char *description) {
    int fd = open_regular(path, error, size);
    if (fd < 0) return -1;
    FILE *file = fdopen(fd, "rb");
    if (!file) {
        int saved_errno = errno;
        close(fd);
        return fail_errno(error, size, "fdopen", path, saved_errno);
    }
    size_t count = fread(contents, 1, maximum + 1, file);
    int bad = ferror(file) || count > maximum;
    if (!bad) contents[count] = 0;
    if (bad && count <= maximum && ferror(file)) {
        int saved_errno = errno;
        fail_errno(error, size, "read", path, saved_errno);
    } else if (count > maximum) {
        if (size) snprintf(error, size, "oversized %s: %s (limit=%zu)", description, path, maximum);
    }
    if (fclose(file)) {
        int saved_errno = errno;
        fail_errno(error, size, "close stream", path, saved_errno);
        bad = 1;
    }
    return bad ? -1 : 0;
}
static int check_artifact(const char *path, char *error, size_t size) {
    int fd = open_regular(path, error, size);
    if (fd < 0) return -1;
    if (close(fd)) {
        int saved_errno = errno;
        return fail_errno(error, size, "close", path, saved_errno);
    }
    return 0;
}
static int option_line(const char *line, size_t length, size_t *index,
                       int crlf, char *error, size_t size) {
    if (crlf && length && line[length - 1] == '\r') --length;
    if (length > 512 || memchr(line, '\r', length) || memchr(line, 0, length))
        return fail(error, size, "invalid CR or overlong desktop option line");
    const char *expected = runner_desktop_option_at(*index);
    if (!expected || strlen(expected) != length || memcmp(line, expected, length))
        return fail(error, size, "unexpected or conflicting desktop launch option");
    ++*index;
    return 0;
}
extern "C" int orbis_desktop_preflight(const RunnerConfig *config, char *error, size_t size) {
    static const char *const native_modules[] = {
        "java", "net", "nio", "zip", "verify", "jimage", "management",
        "management_ext", "jaas", "freetype", "awt", "awt_headless",
        "fontmanager", "javajpeg", "lcms", "mlib_image"
    };
    char path[RUNNER_PATH_CAPACITY * 2], json[65537], modules[65537];
    if (size) error[0] = 0;
    if (!config || config->display != RUNNER_DISPLAY_DESKTOP) return fail(error, size, "desktop preflight requires display=desktop");
#define CHECK_PATH(value) do { if (check_artifact(value, error, size)) return -1; } while (0)
#define CHECK_HOME(suffix) do { snprintf(path, sizeof(path), "%s/%s", config->java_home, suffix); CHECK_PATH(path); } while (0)
#define CHECK_BUNDLE(suffix) do { snprintf(path, sizeof(path), "%s/%s", config->desktop_bundle, suffix); CHECK_PATH(path); } while (0)
    CHECK_HOME("release"); CHECK_HOME("lib/modules"); CHECK_HOME("lib/orbis-modules.list");
    CHECK_HOME("lib/server/libjvm.prx"); CHECK_HOME("lib/fonts/DejaVuSans.ttf");
    CHECK_BUNDLE("LICENSE");
    for (size_t i = 0; i < sizeof(native_modules)/sizeof(native_modules[0]); ++i) {
        snprintf(path, sizeof(path), "%s/lib/lib%s.so", config->java_home, native_modules[i]);
        CHECK_PATH(path);
    }
    for (size_t i = 0; i < runner_desktop_jar_count(); ++i) {
        snprintf(path, sizeof(path), "%s/lib/%s", config->desktop_bundle, runner_desktop_jar_at(i));
        CHECK_PATH(path);
    }
    snprintf(path, sizeof(path), "%s/build-manifest.json", config->desktop_bundle);
    if (bounded_file(path, 65536, json, error, size, "Cacio build manifest")) return -1;
    snprintf(path, sizeof(path), "%s/lib/orbis-capabilities.json", config->java_home);
    if (bounded_file(path, 65536, json, error, size, "runtime capability manifest")) return -1;
    snprintf(path, sizeof(path), "%s/lib/orbis-modules.list", config->java_home);
    if (bounded_file(path, 65536, modules, error, size, "Java module inventory")) return -1;

    snprintf(path, sizeof(path), "%s/launch-options.txt", config->desktop_bundle);
    int fd = open_regular(path, error, size);
    if (fd < 0) return -1;
    FILE *file = fdopen(fd, "rb");
    if (!file) {
        int saved_errno = errno;
        close(fd);
        return fail_errno(error, size, "fdopen", path, saved_errno);
    }
    char line[514];
    size_t line_length = 0, total = 0, index = 0;
    int c, option_error = 0;
    while ((c = fgetc(file)) != EOF) {
        ++total;
        if (total > 16384 || c == 0) { option_error = 1; break; }
        if (c == '\n') {
            if (option_line(line, line_length, &index, 1, error, size)) { option_error = 1; break; }
            line_length = 0;
        } else {
            if (line_length >= 513) { option_error = 1; break; }
            line[line_length++] = (char)c;
        }
    }
    if (!option_error && ferror(file)) {
        int saved_errno = errno;
        fail_errno(error, size, "read", path, saved_errno);
        option_error = 1;
    }
    if (!option_error && line_length && option_line(line, line_length, &index, 0, error, size)) option_error = 1;
    if (fclose(file)) {
        int saved_errno = errno;
        fail_errno(error, size, "close stream", path, saved_errno);
        option_error = 1;
    }
    if (option_error) {
        if (!error || !size || !error[0]) return fail(error, size, "malformed or oversized desktop launch-options.txt");
        return -1;
    }
    if (index != runner_desktop_option_count()) return fail(error, size, "desktop launch option inventory is incomplete");
    if (size) error[0] = 0;
    return 0;
}
