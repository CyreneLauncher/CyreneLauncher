#ifndef ORBIS_MAPPING_PROBE_H
#define ORBIS_MAPPING_PROBE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct orbis_mapping_probe_result {
    size_t successful_maps;
    size_t touched_bytes;
    uintptr_t first_mapping_address;
    int map_errno;
    int unmap_errno;
    size_t requested_bytes;
    uintptr_t aligned_address;
    unsigned int raw_attempts;
    unsigned int aligned_attempts;
    int raw_errno;
    int aligned_errno;
    int release_errno;
};

struct orbis_mapping_variant_result {
    size_t requested_bytes;
    uintptr_t requested_hint;
    uintptr_t address;
    int flags;
    int map_errno;
    int release_errno;
};

int orbis_mapping_probe_run(size_t total_size, size_t mapping_size,
                            struct orbis_mapping_probe_result *result);
int orbis_mapping_probe_hotspot_reservation(
    size_t size, size_t alignment,
    struct orbis_mapping_probe_result *result);
int orbis_mapping_probe_variant(size_t size, void *hint, int prot, int flags,
                                struct orbis_mapping_variant_result *result);
int orbis_available_flexible_memory(uint64_t *bytes);

#ifdef __cplusplus
}
#endif

#endif
