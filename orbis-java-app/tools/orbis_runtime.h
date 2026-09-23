#ifndef ORBIS_JAVA_RUNTIME_H
#define ORBIS_JAVA_RUNTIME_H

#include <stddef.h>
#include <stdint.h>

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
