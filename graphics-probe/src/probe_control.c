#include "probe_control.h"
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int probe_control_requested(const char *env_name, const char *marker_path)
{
    const char *value = env_name ? getenv(env_name) : NULL;
    if (value && strcmp(value, "1") == 0) return 1;
    return marker_path && access(marker_path, F_OK) == 0;
}
