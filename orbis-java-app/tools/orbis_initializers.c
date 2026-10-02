/* Explicit C/C++ initializer entry point for Orbis PRX modules. */
#include <stddef.h>
#include <stdio.h>

__attribute__((visibility("hidden")))
extern void orbis_debug_boundary(const char *text);

typedef void (*orbis_initializer_fn)(void);

extern orbis_initializer_fn __init_array_start[];
extern orbis_initializer_fn __init_array_end[];

static int initializer_state;

__attribute__((visibility("default")))
int orbis_run_initializers(void) {
    orbis_debug_boundary("initializers ENTER\n");
    if (initializer_state == 2) {
        orbis_debug_boundary("initializers RETURN already complete\n");
        return 0;
    }
    if (initializer_state == 1) {
        orbis_debug_boundary("initializers FAILURE recursion\n");
        return -1;
    }

    initializer_state = 1;
    int count = 0;
    for (orbis_initializer_fn *entry = __init_array_start;
         entry != __init_array_end; ++entry) {
        if (*entry != NULL) {
            char detail[192];
            snprintf(detail, sizeof(detail), "constructor ENTER index=%d function=%p\n", count, (void *)*entry);
            orbis_debug_boundary(detail);
            (*entry)();
            snprintf(detail, sizeof(detail), "constructor RETURN index=%d\n", count);
            orbis_debug_boundary(detail);
            ++count;
        }
    }
    initializer_state = 2;
    orbis_debug_boundary("initializers RETURN complete\n");
    return count;
}
