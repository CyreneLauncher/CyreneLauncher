#include "orbis_mapping_probe.h"

#include <errno.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>

// The SDK header incorrectly declares this Orbis export as taking size_t by
// value. The ABI is s32 sceKernelAvailableFlexibleMemorySize(u64 *sizeOut).
extern int32_t orbis_sceKernelAvailableFlexibleMemorySize(uint64_t *size_out)
    __asm__("sceKernelAvailableFlexibleMemorySize");

#define PROBE_PAGE_SIZE 0x4000u
#define PROBE_MAX_BYTES (128u * 1024u * 1024u)
#define PROBE_MAX_MAPS (PROBE_MAX_BYTES / PROBE_PAGE_SIZE)

// Static storage keeps probe bookkeeping out of the libc heap being measured.
static void *probe_mappings[PROBE_MAX_MAPS];

int orbis_available_flexible_memory(uint64_t *bytes) {
    if (bytes == NULL) {
        errno = EINVAL;
        return -1;
    }
    *bytes = 0;
    return orbis_sceKernelAvailableFlexibleMemorySize(bytes);
}

int orbis_mapping_probe_run(size_t total_size, size_t mapping_size,
                            struct orbis_mapping_probe_result *result) {
    if (result == NULL) {
        errno = EINVAL;
        return -1;
    }
    memset(result, 0, sizeof(*result));
    if (total_size == 0 || total_size > PROBE_MAX_BYTES ||
        mapping_size == 0 || mapping_size % PROBE_PAGE_SIZE != 0 ||
        total_size % mapping_size != 0 ||
        total_size / mapping_size > PROBE_MAX_MAPS) {
        result->map_errno = EINVAL;
        errno = EINVAL;
        return -1;
    }

    const size_t count = total_size / mapping_size;
    for (size_t i = 0; i < count; ++i) {
        void *mapped = mmap(NULL, mapping_size, PROT_READ | PROT_WRITE,
                            MAP_PRIVATE | MAP_ANON, -1, 0);
        if (mapped == MAP_FAILED) {
            result->map_errno = errno;
            break;
        }
        probe_mappings[i] = mapped;
        if (i == 0)
            result->first_mapping_address = (uintptr_t)mapped;
        ++result->successful_maps;
        volatile unsigned char *bytes = (volatile unsigned char *)mapped;
        for (size_t offset = 0; offset < mapping_size;
             offset += PROBE_PAGE_SIZE)
            bytes[offset] = (unsigned char)(i + offset / PROBE_PAGE_SIZE);
        result->touched_bytes += mapping_size;
    }

    for (size_t i = result->successful_maps; i > 0; --i) {
        if (munmap(probe_mappings[i - 1], mapping_size) != 0 &&
            result->unmap_errno == 0)
            result->unmap_errno = errno;
        probe_mappings[i - 1] = NULL;
    }
    if (result->map_errno != 0 || result->unmap_errno != 0) {
        errno = result->map_errno != 0 ? result->map_errno : result->unmap_errno;
        return -1;
    }
    return 0;
}

static int release_probe_mapping(void *address, size_t size,
                                 struct orbis_mapping_probe_result *result) {
    if (munmap(address, size) == 0)
        return 0;
    result->release_errno = errno;
    return -1;
}

int orbis_mapping_probe_hotspot_reservation(
    size_t size, size_t alignment,
    struct orbis_mapping_probe_result *result) {
    if (result == NULL) {
        errno = EINVAL;
        return -1;
    }
    memset(result, 0, sizeof(*result));
    result->requested_bytes = size;
    if (size == 0 || size % PROBE_PAGE_SIZE != 0 ||
        alignment < PROBE_PAGE_SIZE ||
        (alignment & (alignment - 1)) != 0 ||
        size > SIZE_MAX - alignment) {
        result->raw_errno = EINVAL;
        result->aligned_errno = EINVAL;
        errno = EINVAL;
        return -1;
    }

    const int flags = MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE;
    result->raw_attempts++;
    void *raw = mmap(NULL, size, PROT_NONE, flags, -1, 0);
    if (raw == MAP_FAILED) {
        result->raw_errno = errno;
    } else if (((uintptr_t)raw & (alignment - 1)) == 0) {
        result->aligned_address = (uintptr_t)raw;
        return release_probe_mapping(raw, size, result);
    } else if (release_probe_mapping(raw, size, result) != 0) {
        errno = result->release_errno;
        return -1;
    }

    const size_t extra_size = size + alignment;
    result->aligned_attempts++;
    void *extra = mmap(NULL, extra_size, PROT_NONE, flags, -1, 0);
    if (extra == MAP_FAILED) {
        result->aligned_errno = errno;
        errno = result->aligned_errno;
        return -1;
    }

    const uintptr_t extra_base = (uintptr_t)extra;
    if (extra_base > UINTPTR_MAX - (alignment - 1)) {
        result->aligned_errno = EOVERFLOW;
        release_probe_mapping(extra, extra_size, result);
        errno = result->aligned_errno;
        return -1;
    }
    const uintptr_t aligned_base =
        (extra_base + alignment - 1) & ~(uintptr_t)(alignment - 1);
    const size_t prefix_size = (size_t)(aligned_base - extra_base);
    const size_t suffix_size = extra_size - prefix_size - size;

    if (prefix_size != 0 && munmap(extra, prefix_size) != 0) {
        result->release_errno = errno;
        release_probe_mapping(extra, extra_size, result);
        errno = result->release_errno;
        return -1;
    }
    if (suffix_size != 0 &&
        munmap((void *)(aligned_base + size), suffix_size) != 0) {
        result->release_errno = errno;
        release_probe_mapping((void *)aligned_base, size, result);
        errno = result->release_errno;
        return -1;
    }

    result->aligned_address = aligned_base;
    return release_probe_mapping((void *)aligned_base, size, result);
}

int orbis_mapping_probe_variant(size_t size, void *hint, int prot, int flags,
                                struct orbis_mapping_variant_result *result) {
    if (result == NULL) {
        errno = EINVAL;
        return -1;
    }
    memset(result, 0, sizeof(*result));
    result->requested_bytes = size;
    result->requested_hint = (uintptr_t)hint;
    result->flags = flags;
    if (size == 0 || size % PROBE_PAGE_SIZE != 0) {
        result->map_errno = EINVAL;
        errno = EINVAL;
        return -1;
    }

    void *mapping = mmap(hint, size, prot, flags, -1, 0);
    if (mapping == MAP_FAILED) {
        result->map_errno = errno;
        return -1;
    }
    result->address = (uintptr_t)mapping;
    if (munmap(mapping, size) != 0) {
        result->release_errno = errno;
        return -1;
    }
    return 0;
}
