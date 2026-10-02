#include "runner_config.h"
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

static int fail(char *error, size_t size, const char *format, ...) {
    va_list args;
    va_start(args, format);
    if (size) vsnprintf(error, size, format, args);
    va_end(args);
    return -1;
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
    RunnerConfig parsed = {"/data/orbis-jdk/jdk", RUNNER_ROOT "/target.jar",
        RUNNER_ROOT, RUNNER_ROOT "/runner-output.log", RUNNER_ROOT "/tmp",
        "/data/orbis-jdk/cacio", "/data/orbis-jdk/graphics", RUNNER_ROOT "/version.json",
        "/data/orbis-jdk/libraries", 0, RUNNER_DISPLAY_CONSOLE};
    const char *keys[] = {"java_home", "jar", "working_directory", "output_log", "temporary_directory", "debug", "display", "desktop_bundle", "graphics_bundle", "version_metadata", "libraries"};
    char *values[] = {parsed.java_home, parsed.jar, parsed.working_directory, parsed.output_log, parsed.temporary_directory, NULL, NULL, parsed.desktop_bundle, parsed.graphics_bundle, parsed.version_metadata, parsed.libraries};
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
