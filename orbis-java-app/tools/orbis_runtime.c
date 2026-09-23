#include "orbis_runtime.h"

#include <errno.h>
#include <orbis/libkernel.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

// crt1 owns the local sceLibcHeapSize and sceLibcHeapExtendedAlloc symbols.
// Binary audit verifies their values because local symbols cannot be read from
// another object. The installed crt1 values are UINT64_MAX and 1 respectively.
static const uint64_t orbis_crt_heap_size = UINT64_MAX;
static const uint32_t orbis_crt_extended_allocation = 1;

#define ORBIS_HEAP_PAGE_SIZE 0x4000u
#define ORBIS_HEAP_ARENA_SIZE (1024u * 1024u)

// The toolchain libc's expand_heap.lo grows each arena exponentially.  Orbis
// eventually rejects the oversized mmap with EINVAL even when malloc only
// needs a small block.  malloc.lo resolves this public hook at final link, so
// provide bounded arenas while preserving its size-in/out contract.
void *__expand_heap(size_t *size) {
    if (size == NULL || *size > SIZE_MAX - (ORBIS_HEAP_PAGE_SIZE - 1)) {
        errno = ENOMEM;
        return NULL;
    }
    size_t mapping_size = (*size + ORBIS_HEAP_PAGE_SIZE - 1)
            & ~(size_t)(ORBIS_HEAP_PAGE_SIZE - 1);
    if (mapping_size < ORBIS_HEAP_ARENA_SIZE)
        mapping_size = ORBIS_HEAP_ARENA_SIZE;
    void *mapping = mmap(NULL, mapping_size, PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANON, -1, 0);
    if (mapping == MAP_FAILED)
        return NULL;
    *size = mapping_size;
    return mapping;
}

int orbis_runtime_allocator_probe(size_t block_size, size_t limit,
                                  struct orbis_alloc_result *result) {
    if (result == NULL || block_size == 0 || limit < block_size)
        return EINVAL;
    memset(result, 0, sizeof(*result));
    result->configured_heap_size = orbis_crt_heap_size;
    result->extended_allocation = orbis_crt_extended_allocation;

    size_t count = limit / block_size;
    void **blocks = calloc(count, sizeof(*blocks));
    if (blocks == NULL) {
        result->first_error = errno != 0 ? errno : ENOMEM;
        return result->first_error;
    }
    size_t allocated = 0;
    for (; allocated < count; ++allocated) {
        blocks[allocated] = malloc(block_size);
        if (blocks[allocated] == NULL) {
            result->first_error = errno != 0 ? errno : ENOMEM;
            break;
        }
        memset(blocks[allocated], (int)(allocated & 0xff), block_size);
        const unsigned char *bytes = blocks[allocated];
        if (bytes[0] != (unsigned char)(allocated & 0xff) ||
            bytes[block_size - 1] != (unsigned char)(allocated & 0xff)) {
            result->first_error = EIO;
            ++allocated;
            break;
        }
        result->allocated_bytes += block_size;
    }
    while (allocated > 0) {
        --allocated;
        free(blocks[allocated]);
        result->released_bytes += block_size;
    }
    free(blocks);
    return result->first_error;
}

__attribute__((noreturn)) void orbis_runtime_exit(int status) {
    char message[96];
    int length = snprintf(message, sizeof(message),
                          "orbis-runtime: terminating status=%d\n", status);
    if (length > 0)
        sceKernelDebugOutText(0, message);

    // OpenOrbis' _exit import enters an unsupported kernel route on retail
    // Orbis. SIGKILL has stable BSD signal numbering and terminates the
    // current process without running unsafe atexit handlers.
    kill(getpid(), SIGKILL);
    for (;;)
        sceKernelUsleep(1000000);
}

__attribute__((noreturn)) void __wrap__exit(int status) {
    orbis_runtime_exit(status);
}
