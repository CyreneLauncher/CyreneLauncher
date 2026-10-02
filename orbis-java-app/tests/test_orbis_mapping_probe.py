#!/usr/bin/env python3
"""Exercise the native mapping probe with real host mappings."""

from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
launcher = (root / "launcher.c").read_text()
source = r'''
#include "orbis_mapping_probe.h"
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <sys/mman.h>

int32_t sceKernelAvailableFlexibleMemorySize(uint64_t *bytes) {
    *bytes = 123456;
    return 0;
}

int main(void) {
    struct orbis_mapping_probe_result result;
    struct orbis_mapping_variant_result variant;
    uint64_t available = 0;
    assert(orbis_available_flexible_memory(&available) == 0);
    assert(available == 123456);
    assert(orbis_mapping_probe_run(65536, 65536, &result) == 0);
    assert(result.successful_maps == 1);
    assert(result.touched_bytes == 65536);
    assert(result.first_mapping_address != 0);
    assert(result.map_errno == 0 && result.unmap_errno == 0);
    assert(orbis_mapping_probe_run(65536, 16384, &result) == 0);
    assert(result.successful_maps == 4);
    assert(result.touched_bytes == 65536);
    assert(result.first_mapping_address != 0);
    assert(result.map_errno == 0 && result.unmap_errno == 0);
    assert(orbis_mapping_probe_run(65536, 0, &result) == -1);
    assert(result.map_errno == EINVAL);
    assert(result.first_mapping_address == 0);
    assert(orbis_mapping_probe_hotspot_reservation(64 * 1024 * 1024,
                                                   16 * 1024 * 1024,
                                                   &result) == 0);
    assert(result.requested_bytes == 64 * 1024 * 1024);
    assert(result.raw_attempts == 1);
    assert(result.raw_errno == 0);
    assert(result.aligned_address != 0);
    assert(result.aligned_address % (16 * 1024 * 1024) == 0);
    assert(result.aligned_attempts <= 1);
    assert(result.release_errno == 0);
    assert(orbis_mapping_probe_variant(16 * 1024 * 1024, NULL, PROT_NONE,
                                       MAP_PRIVATE | MAP_ANONYMOUS,
                                       &variant) == 0);
    assert(variant.requested_bytes == 16 * 1024 * 1024);
    assert(variant.flags == (MAP_PRIVATE | MAP_ANONYMOUS));
    assert(variant.address != 0);
    assert(variant.map_errno == 0);
    assert(variant.release_errno == 0);
    assert(orbis_mapping_probe_variant(16 * 1024 * 1024, NULL,
                                       PROT_READ | PROT_WRITE,
                                       MAP_PRIVATE | MAP_ANONYMOUS,
                                       &variant) == 0);
    assert(variant.flags ==
           (MAP_PRIVATE | MAP_ANONYMOUS));
    assert(variant.address != 0);
    assert(variant.map_errno == 0);
    assert(variant.release_errno == 0);
    void *occupied = mmap(NULL, 16 * 1024 * 1024, PROT_NONE,
                          MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(occupied != MAP_FAILED);
    assert(orbis_mapping_probe_variant(16 * 1024 * 1024, occupied,
                                       PROT_NONE,
                                       MAP_PRIVATE | MAP_ANONYMOUS,
                                       &variant) == 0);
    assert(variant.requested_hint == (uintptr_t)occupied);
    assert(variant.address != (uintptr_t)occupied);
    assert(munmap(occupied, 16 * 1024 * 1024) == 0);
assert(orbis_mapping_probe_hotspot_reservation(64 * 1024 * 1024,
                                                   0,
                                                   &result) == -1);
    assert(result.raw_errno == EINVAL);
assert(result.aligned_errno == EINVAL);
assert(result.aligned_address == 0);
    return 0;
}
'''
with tempfile.TemporaryDirectory() as directory:
    directory = Path(directory)
    harness = directory / "probe_test.c"
    binary = directory / "probe_test"
    harness.write_text(source)
    subprocess.run([
        "cc", "-D_GNU_SOURCE", "-std=c11", "-Wall", "-Wextra", "-Werror",
        "-I", str(root / "tools"), str(harness),
        str(root / "tools" / "orbis_mapping_probe.c"), "-o", str(binary)
    ], check=True)
    subprocess.run([str(binary)], check=True)
assert 'run_hotspot_reservation_probe("post-jvm")' in launcher
assert "sizes_mib[] = {64, 32, 16}" in launcher
assert "address_hints[]" in launcher
assert "mmap-hint size_mib=" in launcher
print("Orbis mapping probe host test passed")
