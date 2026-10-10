#ifndef ORBIS_RUNNER_CONFIG_H
#define ORBIS_RUNNER_CONFIG_H
#include <stddef.h>
#define RUNNER_CONFIG_PATH "/data/orbis-jdk/runner/runner.conf"
#define RUNNER_ROOT "/data/orbis-jdk/runner"
/* Where the launcher downloads the runtimes the package does not carry (RuntimeInstaller). */
#define RUNNER_DOWNLOADED_RUNTIMES "/data/orbis-jdk/runtimes"
/* One copy in the package for every runtime. */
#define RUNNER_GRAPHICS_BUNDLE "/app0/orbis/graphics"
#define RUNNER_DESKTOP_BUNDLE "/app0/orbis/desktop"
#define RUNNER_LAUNCHER_JAR RUNNER_GRAPHICS_BUNDLE "/lib/orbis-launcher.jar"
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
    /* Keyboard/mouse diagnostics on UART, separate from debug: off unless asked for. */
    int input_diagnostics;
} RunnerConfig;
int runner_config_load(const char *path, RunnerConfig *config, char *error, size_t error_size);
int runner_config_seed_defaults(const char *defaults, const char *root, char *error, size_t error_size);
int runner_config_installed(RunnerConfig *config, char *error, size_t error_size);
int runner_option_reserved(const char *option);
#define RUNNER_VM_OPTION_CAPACITY 8192
#define RUNNER_VM_OPTION_COUNT 96
/* Loader module options use a private prefix and are confined to configured libraries. */
int runner_loader_option(const char *option, char *output, size_t capacity, const char *libraries);
/* Desktop-safe option inventory shared with the native preflight/launcher. */
size_t runner_desktop_option_count(void);
const char *runner_desktop_option_at(size_t index);
size_t runner_desktop_jar_count(void);
const char *runner_desktop_jar_at(size_t index);
#ifdef __cplusplus
}
#endif
#endif
