#ifndef ORBIS_DESKTOP_PREFLIGHT_H
#define ORBIS_DESKTOP_PREFLIGHT_H
#include "runner_config.h"
#ifdef __cplusplus
extern "C" {
#endif
/* Opens and bounds every desktop artifact before JVM creation. */
int orbis_desktop_preflight(const RunnerConfig *config, char *error, size_t error_size);
#ifdef __cplusplus
}
#endif
#endif
