#define _POSIX_C_SOURCE 200809L
#include "probe_control.h"
#include <assert.h>
#include <fcntl.h>
#include <stdlib.h>
#include <unistd.h>

int main(void) {
    char path[] = "/tmp/probe-marker-XXXXXX";
    int fd = mkstemp(path);
    assert(fd >= 0);
    close(fd);
    unlink(path);
    unsetenv("PROBE_TEST_MODE");
    assert(!probe_control_requested("PROBE_TEST_MODE", path));
    setenv("PROBE_TEST_MODE", "0", 1);
    assert(!probe_control_requested("PROBE_TEST_MODE", path));
    setenv("PROBE_TEST_MODE", "10", 1);
    assert(!probe_control_requested("PROBE_TEST_MODE", path));
    setenv("PROBE_TEST_MODE", "1", 1);
    assert(probe_control_requested("PROBE_TEST_MODE", path));
    unsetenv("PROBE_TEST_MODE");
    fd = creat(path, 0600);
    assert(fd >= 0);
    close(fd);
    assert(probe_control_requested("PROBE_TEST_MODE", path));
    unlink(path);
    assert(!probe_control_requested("PROBE_TEST_MODE", path));
    return 0;
}
