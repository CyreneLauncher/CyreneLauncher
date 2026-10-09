#define _POSIX_C_SOURCE 200809L
#include "runner_config.h"
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int fail(char *error, size_t size, const char *format, ...) {
    va_list args;
    va_start(args, format);
    if (size) vsnprintf(error, size, format, args);
    va_end(args);
    return -1;
}
int runner_config_seed_defaults(const char *defaults, const char *root, char *error, size_t size) {
    char directory[RUNNER_PATH_CAPACITY];
    if (*root != '/' || strlen(root) >= sizeof(directory)) return fail(error, size, "invalid runner directory");
    strcpy(directory, root);
    for (char *part = directory + 1; ; ++part) {
        if (*part == '/' || !*part) {
            char saved = *part; *part = 0;
            if (mkdir(directory, 0777) < 0 && errno != EEXIST)
                return fail(error, size, "cannot create %s: %s", directory, strerror(errno));
            *part = saved;
            if (!saved) break;
        }
    }
    const char *names[] = {"runner.conf", "jvm-options.txt"};
    for (unsigned i = 0; i < sizeof(names)/sizeof(names[0]); ++i) {
        char source[1024], target[1024], temporary[1100], buffer[4096];
        if (snprintf(source, sizeof(source), "%s/%s", defaults, names[i]) >= (int)sizeof(source) ||
            snprintf(target, sizeof(target), "%s/%s", root, names[i]) >= (int)sizeof(target))
            return fail(error, size, "runner defaults path too long");
        // Probe by opening: the SDK's lstat is musl's fstatat wrapper, which Orbis lacks (ENOSYS).
        int present = open(target, O_RDONLY);
        if (present >= 0) { close(present); continue; }
        if (errno != ENOENT) return fail(error, size, "cannot inspect %s: %s", target, strerror(errno));
        snprintf(temporary, sizeof(temporary), "%s.seed-XXXXXX", target);
        int out = mkstemp(temporary);
        if (out < 0) return fail(error, size, "cannot initialize %s: %s", target, strerror(errno));
        int in = open(source, O_RDONLY), saved_errno = 0;
        if (in < 0) saved_errno = errno;
        while (!saved_errno) {
            ssize_t count = read(in, buffer, sizeof(buffer));
            if (count < 0) { if (errno == EINTR) continue; saved_errno = errno; break; }
            if (!count) break;
            ssize_t written = 0;
            while (written < count) {
                ssize_t result = write(out, buffer + written, (size_t)(count - written));
                if (result < 0 && errno == EINTR) continue;
                if (result <= 0) { saved_errno = result < 0 ? errno : EIO; break; }
                written += result;
            }
        }
        if (in >= 0 && close(in) < 0 && !saved_errno) saved_errno = errno;
        if (!saved_errno && fsync(out) < 0) saved_errno = errno;
        if (close(out) < 0 && !saved_errno) saved_errno = errno;
        if (saved_errno) {
            unlink(temporary);
            return fail(error, size, "cannot initialize %s: %s", target, strerror(saved_errno));
        }
        // Hard-link publication is atomic and exclusive: never replace user settings.
        int published = link(temporary, target);
        saved_errno = errno;
        unlink(temporary);
        if (published < 0 && saved_errno != EEXIST)
            return fail(error, size, "cannot publish %s: %s", target, strerror(saved_errno));
    }
    return 0;
}

int runner_config_installed(RunnerConfig *config, char *error, size_t size) {
    const char *names[] = {"jdk17", "jdk21", "jdk25", "jdk"};
    const int features[] = {17, 21, 25, 17};
    size_t length = strlen(config->java_home);
    while (length > 1 && config->java_home[length - 1] == '/') config->java_home[--length] = 0;
    int feature = 0;
    for (unsigned i = 0; i < sizeof(names)/sizeof(names[0]); ++i) {
        char legacy[128], installed[128];
        snprintf(legacy, sizeof(legacy), "/data/orbis-jdk/%s", names[i]);
        snprintf(installed, sizeof(installed), "/app0/runtimes/jdk%d", features[i]);
        size_t n = strlen(legacy);
        if (!strcmp(config->java_home, installed) ||
            (!strncmp(config->java_home, legacy, n) &&
             (!config->java_home[n] || !strcmp(config->java_home + n, "/release-desktop")))) {
            feature = features[i]; break;
        }
    }
    if (!feature) return fail(error, size, "select a packaged JDK 17, 21 or 25; unsupported java_home=%s", config->java_home);
    snprintf(config->java_home, sizeof(config->java_home), "/app0/runtimes/jdk%d", feature);
    snprintf(config->graphics_bundle, sizeof(config->graphics_bundle), "/app0/runtimes/jdk%d/lib/orbis/graphics", feature);
    snprintf(config->desktop_bundle, sizeof(config->desktop_bundle), "/app0/runtimes/jdk%d/lib/orbis/desktop", feature);
    return 0;
}
static int config_space(unsigned char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == '\v';
}

static char *trim(char *text) {
    while (config_space((unsigned char)*text)) ++text;
    size_t n = strlen(text);
    while (n && config_space((unsigned char)text[n-1])) text[--n] = 0;
    return text;
}
static int debug_log_collision(const char *path) {
    char normalized[RUNNER_PATH_CAPACITY];
    size_t ends[RUNNER_PATH_CAPACITY], depth = 0, used = 1;
    normalized[0] = '/';
    while (*path) {
        while (*path == '/') ++path;
        const char *start = path;
        while (*path && *path != '/') ++path;
        size_t length = (size_t)(path - start);
        if (!length || (length == 1 && start[0] == '.')) continue;
        if (length == 2 && start[0] == '.' && start[1] == '.') {
            if (depth) used = ends[--depth];
            continue;
        }
        ends[depth++] = used;
        if (used > 1) normalized[used++] = '/';
        memcpy(normalized + used, start, length); used += length;
    }
    normalized[used] = 0;
    return !strcmp(normalized, RUNNER_ROOT "/runner-debug.log") ||
           !strcmp(normalized, RUNNER_ROOT "/runner-debug.log.1");
}
int runner_config_load(const char *path, RunnerConfig *config, char *error, size_t size) {
    RunnerConfig parsed = {"/app0/runtimes/jdk17", "/app0/runtimes/jdk17/lib/orbis/graphics/lib/orbis-launcher.jar",
        RUNNER_ROOT, RUNNER_ROOT "/runner-output.log", RUNNER_ROOT "/tmp",
        "/app0/runtimes/jdk17/lib/orbis/desktop", "/app0/runtimes/jdk17/lib/orbis/graphics", "/app0/manifests/launcher.json",
        "/data/orbis-jdk/libraries", 0, RUNNER_DISPLAY_GPU, 0};
    const char *keys[] = {"java_home", "jar", "working_directory", "output_log", "temporary_directory", "debug", "display", "desktop_bundle", "graphics_bundle", "version_metadata", "libraries", "input_diagnostics"};
    char *values[] = {parsed.java_home, parsed.jar, parsed.working_directory, parsed.output_log, parsed.temporary_directory, NULL, NULL, parsed.desktop_bundle, parsed.graphics_bundle, parsed.version_metadata, parsed.libraries, NULL};
    unsigned seen = 0;
    if (size) error[0] = 0;
    FILE *file = fopen(path, "rb");
    if (!file) {
        if (errno == ENOENT) { *config = parsed; return 0; }
        return fail(error, size, "cannot read configuration %s: %s", path, strerror(errno));
    }
    char line[1024];
    unsigned number = 0;
    int result = 0;
    for (;;) {
        size_t length = 0;
        int ch;
        while ((ch = (fgetc)(file)) != EOF && ch != '\n') {
            if (ch == 0 || length == sizeof(line)-1) {
                result = fail(error, size, "%s line %u: NUL byte or overlong line", path, number+1);
                goto done;
            }
            line[length++] = (char)ch;
        }
        if (ch == EOF && !length) break;
        line[length] = 0;
        ++number;
        char *key = trim(line);
        if (!*key || *key == '#') continue;
        char *equal = strchr(key, '=');
        if (!equal) { result = fail(error, size, "%s line %u: expected key=value", path, number); goto done; }
        *equal++ = 0;
        key = trim(key);
        char *value = trim(equal);
        unsigned i;
        for (i = 0; i < sizeof(keys)/sizeof(keys[0]) && strcmp(keys[i], key); ++i) {}
        if (i == sizeof(keys)/sizeof(keys[0]) || (seen & (1u << i))) {
            result = fail(error, size, "%s line %u: unknown or duplicate key %s", path, number, key); goto done;
        }
        if (i == 5) {
            if (!strcmp(value, "true")) parsed.debug = 1;
            else if (!strcmp(value, "false")) parsed.debug = 0;
            else { result = fail(error, size, "%s line %u: debug requires true or false", path, number); goto done; }
            seen |= 1u << i;
            continue;
        }
        if (i == 11) {
            if (!strcmp(value, "true")) parsed.input_diagnostics = 1;
            else if (!strcmp(value, "false")) parsed.input_diagnostics = 0;
            else { result = fail(error, size, "%s line %u: input_diagnostics requires true or false", path, number); goto done; }
            seen |= 1u << i;
            continue;
        }
        if (i == 6) {
            if (!strcmp(value, "console")) parsed.display = RUNNER_DISPLAY_CONSOLE;
            else if (!strcmp(value, "desktop")) parsed.display = RUNNER_DISPLAY_DESKTOP;
            else if (!strcmp(value, "gpu")) parsed.display = RUNNER_DISPLAY_GPU;
            else { result = fail(error, size, "%s line %u: display requires console, desktop or gpu", path, number); goto done; }
            seen |= 1u << i;
            continue;
        }
        if (*value != '/' || strlen(value) >= RUNNER_PATH_CAPACITY) {
            result = fail(error, size, "%s line %u: %s requires an absolute path shorter than %d bytes", path, number, key, RUNNER_PATH_CAPACITY); goto done;
        }
        strcpy(values[i], value);
        if ((i == 7 || i == 8) && strchr(value, ':')) {
            result = fail(error, size, "%s line %u: %s may not contain ':'", path, number, key); goto done;
        }
        seen |= 1u << i;
    }
    if ((ferror)(file)) result = fail(error, size, "cannot read configuration %s: %s", path, strerror(errno));
done:
    if (fclose(file) && !result) result = fail(error, size, "cannot close configuration %s: %s", path, strerror(errno));
    if (!result && parsed.debug &&
        (debug_log_collision(parsed.output_log) || debug_log_collision(parsed.jar)))
        result = fail(error, size, "debug log paths are reserved; jar/output_log must use separate files");
    if (!result) *config = parsed;
    return result;
}
int runner_option_reserved(const char *option) {
    static const char *properties[] = {"java.home", "java.class.path", "java.library.path", "java.io.tmpdir", "user.dir", "java.system.class.loader", "runner.display", "runner.desktop.bundle", "runner.graphics.bundle", "runner.gpu.metadata", "runner.gpu.libraries", "runner.jar", "runner.output.log", "runner.debug.log", "orbis.runner.root", "orbis.runner.screen.enabled", "orbis.module.finder.imageFallback", "orbis.module.names.file", "orbis.module.names.list", "jdk.module.path", "jdk.module.upgrade.path", "jdk.module.main", "jdk.module.addmods", "sun.boot.library.path", "sun.boot.class.path"};
    if (!strncmp(option, "-D", 2)) {
        const char *key = option + 2;
        size_t n = strcspn(key, "=");
        for (size_t i=0; i<sizeof(properties)/sizeof(properties[0]); ++i)
            if (strlen(properties[i]) == n && !strncmp(key, properties[i], n)) return 1;
        if (!strncmp(key, "jdk.module.", 11) || !strncmp(key, "orbis.module.", 13)) return 1;
    }
    static const char *flags[] = {"--module-path", "--upgrade-module-path", "--patch-module", "--limit-modules", "--add-modules", "-p", "-classpath", "-cp", "--class-path", "-Xbootclasspath"};
    for (size_t i=0; i<sizeof(flags)/sizeof(flags[0]); ++i) {
        size_t n = strlen(flags[i]);
        if (!strncmp(option, flags[i], n) && (option[n] == 0 || option[n] == '=' || option[n] == ':' || option[n] == '/' || option[n] == ' ')) return 1;
    }
    return 0;
}

static const char *const desktop_options[] = {
    "-Djava.awt.headless=false",
    "-Dcacio.managed.screensize=1280x720",
    "-Dorbis.awt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit",
    "-Dorbis.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment",
    "-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel",
    "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.font=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.java2d.pipe=ALL-UNNAMED",
    "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED",
    "--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
    "--add-exports=java.base/sun.security.action=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt=ALL-UNNAMED"
};
size_t runner_desktop_option_count(void) { return sizeof(desktop_options)/sizeof(desktop_options[0]); }
const char *runner_desktop_option_at(size_t index) {
    return index < runner_desktop_option_count() ? desktop_options[index] : NULL;
}
static const char *const desktop_jars[] = {
    "cacio-shared.jar", "cacio-tta.jar", "orbis-desktop-adapter.jar"
};
size_t runner_desktop_jar_count(void) { return sizeof(desktop_jars)/sizeof(desktop_jars[0]); }
const char *runner_desktop_jar_at(size_t index) {
    return index < runner_desktop_jar_count() ? desktop_jars[index] : NULL;
}
