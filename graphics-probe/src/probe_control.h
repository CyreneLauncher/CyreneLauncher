#ifndef PROBE_CONTROL_H
#define PROBE_CONTROL_H

/* Exact environment value 1 or an existing device marker requests a failure. */
int probe_control_requested(const char *env_name, const char *marker_path);

#endif
