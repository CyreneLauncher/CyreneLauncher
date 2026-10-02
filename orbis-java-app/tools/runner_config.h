#ifndef ORBIS_RUNNER_CONFIG_H
#define ORBIS_RUNNER_CONFIG_H
#include <stddef.h>
#define RUNNER_CONFIG_PATH "/data/orbis-jdk/runner/runner.conf"
#define RUNNER_ROOT "/data/orbis-jdk/runner"
#define RUNNER_PATH_CAPACITY 512
enum RunnerDisplay { RUNNER_DISPLAY_CONSOLE = 0, RUNNER_DISPLAY_DESKTOP = 1, RUNNER_DISPLAY_GPU = 2 };
#ifdef __cplusplus
extern "C" {
#endif
typedef struct RunnerConfig {
    char java_home[RUNNER_PATH_CAPACITY];
    char jar[RUNNER_PATH_CAPACITY];
    char working_directory[RUNNER_PATH_CAPACITY];
    char output_log[RUNNER_PATH_CAPACITY];
    char temporary_directory[RUNNER_PATH_CAPACITY];
    char desktop_bundle[RUNNER_PATH_CAPACITY];
    char graphics_bundle[RUNNER_PATH_CAPACITY];
    char version_metadata[RUNNER_PATH_CAPACITY];
    char libraries[RUNNER_PATH_CAPACITY];
    int debug;
    int display;
} RunnerConfig;
int runner_config_load(const char *path, RunnerConfig *config, char *error, size_t error_size);
int runner_option_reserved(const char *option);
/* Desktop-safe option inventory shared with the native preflight/launcher. */
size_t runner_desktop_option_count(void);
const char *runner_desktop_option_at(size_t index);
size_t runner_desktop_jar_count(void);
const char *runner_desktop_jar_at(size_t index);
#ifdef __cplusplus
}
#endif
#endif
