#ifndef ORBIS_JAVA_RUNTIME_H
#define ORBIS_JAVA_RUNTIME_H

#include <stddef.h>
#include <stdint.h>

/* The toolchain's musl time.h has Linux clock ids: its CLOCK_MONOTONIC (1) is FreeBSD's
 * CLOCK_VIRTUAL on Orbis, the process's user CPU time, which runs several times slower than real
 * time under load. FreeBSD's CLOCK_MONOTONIC is 4 (HotSpot uses it the same way). */
#define ORBIS_CLOCK_MONOTONIC ((clockid_t)4)

#ifdef __cplusplus
extern "C" {
#endif

struct orbis_alloc_result {
    size_t allocated_bytes;
    size_t released_bytes;
    int first_error;
    uint64_t configured_heap_size;
    uint32_t extended_allocation;
};

int orbis_runtime_allocator_probe(size_t block_size, size_t limit,
                                  struct orbis_alloc_result *result);
__attribute__((noreturn)) void orbis_runtime_exit(int status);
__attribute__((noreturn)) void __wrap__exit(int status);

#ifdef __cplusplus
}
#endif

#endif
