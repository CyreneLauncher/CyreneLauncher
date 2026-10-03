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
static int heap_failure_probed;

static void *map_direct_heap(size_t size, off_t *direct_offset_out) {
    off_t direct_offset = 0;
    int allocate_result = sceKernelAllocateDirectMemory(
            0, ORBIS_KERNEL_MAIN_DMEM_SIZE, size, ORBIS_HEAP_PAGE_SIZE,
            ORBIS_KERNEL_WB_ONION, &direct_offset);
    if (allocate_result != 0) {
        char diagnostic[160];
        int length = snprintf(diagnostic, sizeof(diagnostic),
                              "orbis-runtime: direct heap allocation failed size=%zu result=0x%x\n",
                              size, (unsigned int)allocate_result);
        if (length > 0)
            sceKernelDebugOutText(0, diagnostic);
        errno = ENOMEM;
        return MAP_FAILED;
    }

    void *mapping = NULL;
    int map_result = sceKernelMapDirectMemory(
            &mapping, size, PROT_READ | PROT_WRITE, 0, direct_offset,
            ORBIS_HEAP_PAGE_SIZE);
    if (map_result != 0 || mapping == NULL) {
        int release_result = sceKernelReleaseDirectMemory(direct_offset, size);
        char diagnostic[192];
        int length = snprintf(diagnostic, sizeof(diagnostic),
                              "orbis-runtime: direct heap mapping failed size=%zu result=0x%x release=0x%x\n",
                              size, (unsigned int)map_result,
                              (unsigned int)release_result);
        if (length > 0)
            sceKernelDebugOutText(0, diagnostic);
        errno = ENOMEM;
        return MAP_FAILED;
    }

    *direct_offset_out = direct_offset;
    return mapping;
}

static void probe_heap_mapping_sizes(void) {
    static const size_t probe_sizes[] = {
        0x4000, 0x8000, 0x10000, 0x18000, 0x20000, 0x40000, 0x100000
    };
    const int saved_errno = errno;
    for (size_t i = 0; i < sizeof(probe_sizes) / sizeof(probe_sizes[0]); ++i) {
        errno = 0;
        void *probe = mmap(NULL, probe_sizes[i], PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANON, -1, 0);
        const int probe_errno = errno;
        int unmapped = 1;
        if (probe != MAP_FAILED)
            unmapped = munmap(probe, probe_sizes[i]) == 0;
        char diagnostic[160];
        int length = snprintf(diagnostic, sizeof(diagnostic),
                              "orbis-runtime: mmap probe size=%zu result=%p errno=%d unmapped=%d\n",
                              probe_sizes[i], probe, probe_errno, unmapped);
        if (length > 0)
            sceKernelDebugOutText(0, diagnostic);
    }
    errno = saved_errno;
}

// Every anonymous mapping is charged to the process's flexible-memory budget (~448 MiB, the
// maximum a base-PS4 title may declare), reservations included. Once it is spent, no anonymous
// mapping of 1 MiB or more can be made - so no new thread stack - while gigabytes of direct
// memory are unused. Heap growth and large blocks therefore come from direct memory first,
// zeroed because musl assumes fresh mappings are, and fall back to anonymous memory.
static void *map_direct_zeroed(size_t size, off_t *direct_offset_out) {
    void *mapping = map_direct_heap(size, direct_offset_out);
    if (mapping != MAP_FAILED)
        memset(mapping, 0, size);
    return mapping;
}

// Give each OpenOrbis libc heap growth its own mapping, with a 1-MiB minimum,
// from direct memory first (see above); heap growth is never returned.
void *__expand_heap(size_t *size) {
    if (size == NULL || *size > SIZE_MAX - (ORBIS_HEAP_PAGE_SIZE - 1)) {
        errno = ENOMEM;
        return NULL;
    }
    size_t mapping_size = (*size + ORBIS_HEAP_PAGE_SIZE - 1)
            & ~(size_t)(ORBIS_HEAP_PAGE_SIZE - 1);
    if (mapping_size < ORBIS_HEAP_ARENA_SIZE)
        mapping_size = ORBIS_HEAP_ARENA_SIZE;

    off_t growth_offset = 0;
    void *direct_growth = map_direct_zeroed(mapping_size, &growth_offset);
    if (direct_growth != MAP_FAILED) {
        *size = mapping_size;
        return direct_growth;
    }

    void *mapping = mmap(NULL, mapping_size, PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANON, -1, 0);
    if (mapping == MAP_FAILED) {
        const int map_errno = errno;
        char diagnostic[192];
        int length = snprintf(diagnostic, sizeof(diagnostic),
                              "orbis-runtime: heap mapping failed requested=%zu mapped=%zu errno=%d\n",
                              *size, mapping_size, map_errno);
        if (length > 0)
            sceKernelDebugOutText(0, diagnostic);
        if (!heap_failure_probed) {
            heap_failure_probed = 1;
            probe_heap_mapping_sizes();
        }
        off_t direct_offset = 0;
        mapping = map_direct_heap(mapping_size, &direct_offset);
        if (mapping != MAP_FAILED) {
            char direct_diagnostic[192];
            int direct_length = snprintf(
                    direct_diagnostic, sizeof(direct_diagnostic),
                    "orbis-runtime: direct heap fallback size=%zu offset=0x%llx address=%p\n",
                    mapping_size, (unsigned long long)direct_offset, mapping);
            if (direct_length > 0)
                sceKernelDebugOutText(0, direct_diagnostic);
            *size = mapping_size;
            return mapping;
        }
        errno = map_errno;
        return NULL;
    }
    *size = mapping_size;
    return mapping;
}

// musl's malloc maps every allocation above its MMAP_THRESHOLD (~224 KiB)
// through __mmap/__munmap and grows those with __mremap, rounding to 4 KiB.
// Route that path like __expand_heap: whole 16-KiB Orbis pages, and Orbis
// direct memory when anonymous mmap cannot supply them. Direct mappings are
// recorded so __munmap can release their direct memory.
#define ORBIS_DIRECT_MAPPINGS 8192
static struct { void *address; size_t size; off_t offset; } direct_mappings[ORBIS_DIRECT_MAPPINGS];
static volatile int direct_mappings_lock;
static int direct_fallback_reported;

static void lock_direct_mappings(void) {
    while (__atomic_exchange_n(&direct_mappings_lock, 1, __ATOMIC_ACQUIRE))
        sceKernelUsleep(0);
}

static void unlock_direct_mappings(void) {
    __atomic_store_n(&direct_mappings_lock, 0, __ATOMIC_RELEASE);
}

static size_t round_heap_pages(size_t size) {
    return (size + ORBIS_HEAP_PAGE_SIZE - 1) & ~(size_t)(ORBIS_HEAP_PAGE_SIZE - 1);
}

void *__mmap(void *address, size_t size, int prot, int flags, int fd, off_t offset) {
    if (address != NULL || fd != -1 || !(flags & MAP_ANON))
        return mmap(address, size, prot, flags, fd, offset);
    if (size == 0 || size > SIZE_MAX - (ORBIS_HEAP_PAGE_SIZE - 1)) {
        errno = ENOMEM;
        return MAP_FAILED;
    }
    size_t mapping_size = round_heap_pages(size);
    void *mapping = MAP_FAILED;
    if (prot == (PROT_READ | PROT_WRITE)) {
        // Direct memory first (see map_direct_zeroed); the table lets __munmap return it.
        lock_direct_mappings();
        int slot = -1;
        for (int i = 0; i < ORBIS_DIRECT_MAPPINGS && slot < 0; ++i)
            if (direct_mappings[i].address == NULL)
                slot = i;
        if (slot >= 0) {
            off_t direct_offset = 0;
            mapping = map_direct_zeroed(mapping_size, &direct_offset);
            if (mapping != MAP_FAILED) {
                direct_mappings[slot].address = mapping;
                direct_mappings[slot].size = mapping_size;
                direct_mappings[slot].offset = direct_offset;
            }
        }
        unlock_direct_mappings();
        if (mapping != MAP_FAILED)
            return mapping;
    }
    mapping = mmap(NULL, mapping_size, prot, flags, -1, 0);
    if (mapping != MAP_FAILED)
        return mapping;

    const int map_errno = errno;
    lock_direct_mappings();
    int slot = -1;
    for (int i = 0; i < ORBIS_DIRECT_MAPPINGS && slot < 0; ++i)
        if (direct_mappings[i].address == NULL)
            slot = i;
    off_t direct_offset = 0;
    if (slot >= 0)
        mapping = map_direct_heap(mapping_size, &direct_offset);
    if (mapping != MAP_FAILED) {
        direct_mappings[slot].address = mapping;
        direct_mappings[slot].size = mapping_size;
        direct_mappings[slot].offset = direct_offset;
    }
    const int report = !direct_fallback_reported;
    direct_fallback_reported = 1;
    unlock_direct_mappings();
    if (report) {
        char diagnostic[192];
        int length = snprintf(diagnostic, sizeof(diagnostic),
                              "orbis-runtime: large allocation mapping failed size=%zu errno=%d; "
                              "direct memory fallback %s\n", mapping_size, map_errno,
                              mapping != MAP_FAILED ? "ok" : slot < 0 ? "table full" : "failed");
        if (length > 0)
            sceKernelDebugOutText(0, diagnostic);
    }
    if (mapping == MAP_FAILED)
        errno = ENOMEM;
    return mapping;
}

int __munmap(void *address, size_t size) {
    size_t mapping_size = round_heap_pages(size);
    off_t direct_offset = 0;
    int direct = 0;
    lock_direct_mappings();
    for (int i = 0; i < ORBIS_DIRECT_MAPPINGS; ++i) {
        if (direct_mappings[i].address == address) {
            mapping_size = direct_mappings[i].size;
            direct_offset = direct_mappings[i].offset;
            direct_mappings[i].address = NULL;
            direct = 1;
            break;
        }
    }
    unlock_direct_mappings();
    int result = munmap(address, mapping_size);
    if (direct && result == 0)
        sceKernelReleaseDirectMemory(direct_offset, mapping_size);
    return result;
}

// No mremap on Orbis; musl's realloc then allocates, copies and frees.
void *__mremap(void *old_address, size_t old_size, size_t new_size, int flags, ...) {
    (void)old_address; (void)old_size; (void)new_size; (void)flags;
    errno = ENOMEM;
    return MAP_FAILED;
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
