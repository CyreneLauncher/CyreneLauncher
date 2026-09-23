/* Explicit C/C++ initializer entry point for Orbis PRX modules. */
#include <stddef.h>

typedef void (*orbis_initializer_fn)(void);

extern orbis_initializer_fn __init_array_start[];
extern orbis_initializer_fn __init_array_end[];

static int initializer_state;

__attribute__((visibility("default")))
int orbis_run_initializers(void) {
    if (initializer_state == 2) {
        return 0;
    }
    if (initializer_state == 1) {
        return -1;
    }

    initializer_state = 1;
    int count = 0;
    for (orbis_initializer_fn *entry = __init_array_start;
         entry != __init_array_end; ++entry) {
        if (*entry != NULL) {
            (*entry)();
            ++count;
        }
    }
    initializer_state = 2;
    return count;
}
