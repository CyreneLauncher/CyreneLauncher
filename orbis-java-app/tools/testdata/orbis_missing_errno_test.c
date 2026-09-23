#include <assert.h>
#include <errno.h>

#include "orbis_errno.h"

int main(void) {
    assert(orbis_normalize_missing_errno(ESRCH) == ENOENT);
    assert(orbis_normalize_missing_errno(ENOENT) == ENOENT);
    assert(orbis_normalize_missing_errno(EACCES) == EACCES);
    return 0;
}
