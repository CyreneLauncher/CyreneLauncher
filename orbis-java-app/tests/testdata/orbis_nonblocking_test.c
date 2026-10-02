#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>

static int current_flags;
static int set_changes_flags;

static int test_fcntl(int fd, int command, ...);

#define fcntl test_fcntl
#include "orbis_nonblocking.h"
#undef fcntl

static int test_fcntl(int fd, int command, ...) {
    va_list arguments;
    int requested_flags;

    assert(fd == 7);
    if (command == F_GETFL) {
        return current_flags;
    }

    assert(command == F_SETFL);
    va_start(arguments, command);
    requested_flags = va_arg(arguments, int);
    va_end(arguments);
    if (set_changes_flags) {
        current_flags = requested_flags;
    }
    errno = EACCES;
    return -1;
}

int main(void) {
    current_flags = O_RDWR;
    set_changes_flags = 1;
    assert(orbis_configure_blocking(7, 0) == 0);
    assert((current_flags & O_NONBLOCK) != 0);

    current_flags = O_RDWR;
    set_changes_flags = 0;
    errno = 0;
    assert(orbis_configure_blocking(7, 0) == -1);
    assert(errno == EACCES);

    current_flags = O_RDWR | O_NONBLOCK;
    set_changes_flags = 1;
    assert(orbis_configure_blocking(7, 1) == 0);
    assert((current_flags & O_NONBLOCK) == 0);
    return 0;
}
