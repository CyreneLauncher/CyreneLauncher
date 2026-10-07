#!/usr/bin/env python3
"""Contract tests for shared Orbis allocation and termination support."""

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
runtime = (ROOT / "tools" / "orbis_runtime.c").read_text()
header = (ROOT / "tools" / "orbis_runtime.h").read_text()
hotspot = (ROOT.parent / "src" / "hotspot" / "os" / "orbis" / "os_orbis.cpp").read_text()
debug = (ROOT.parent / "src" / "hotspot" / "share" / "utilities" / "debug.cpp").read_text()
os_runtime = (ROOT.parent / "src" / "hotspot" / "share" / "runtime" / "os.cpp").read_text()

assert "orbis_runtime_allocator_probe" in runtime
assert "struct orbis_alloc_result" in header
assert "sceLibcHeapSize" in runtime
assert "sceLibcHeapExtendedAlloc" in runtime
assert "orbis_runtime_exit" in runtime
# Linux clock ids from the toolchain headers map to FreeBSD's before libkernel sees them.
assert '__asm__("sceKernelClockGettime")' in runtime
assert re.search(r"__attribute__\(\(weak\)\) int clock_gettime\(clockid_t id, struct timespec \*ts\)", runtime)
assert "case 1: case 4: case 6: case 7: native = 4; break;" in runtime
assert "case 2: case 3: native = 2; break;" in runtime
# UFS leaves inode-0 entries behind renames; readdir must skip them.
assert re.search(r"struct dirent \*readdir\(DIR \*dir\)[\s\S]*?if \(de->fileno != 0\)", runtime)
assert "readdir64(DIR *dir) __attribute__((alias(\"readdir\")))" in runtime
assert "void *__expand_heap(size_t *size)" in runtime
assert "ORBIS_HEAP_PAGE_SIZE" in runtime
assert "ORBIS_HEAP_ARENA_SIZE (1024u * 1024u)" in runtime
assert re.search(r"if\s*\(mapping_size\s*<\s*ORBIS_HEAP_ARENA_SIZE\)", runtime)
assert re.search(r"mapping_size\s*=\s*ORBIS_HEAP_ARENA_SIZE", runtime)
assert "MAP_PRIVATE | MAP_ANON, -1, 0" in runtime
assert "sceKernelAllocateDirectMemory" in runtime
assert "sceKernelMapDirectMemory" in runtime
assert "sceKernelReleaseDirectMemory" in runtime
assert re.search(r"if\s*\(mapping\s*==\s*MAP_FAILED\)\s*\{[\s\S]*?map_direct_heap\(mapping_size, &direct_offset\)", runtime)
assert re.search(r"sceKernelReleaseDirectMemory\(direct_offset, size\)", runtime)
assert "direct heap fallback" in runtime
assert "*size = mapping_size" in runtime
assert "orbis-runtime: mmap probe size=%zu result=%p errno=%d" in runtime
assert re.search(
    r"probe_sizes\[\]\s*=\s*\{\s*0x4000,\s*0x8000,\s*0x10000,\s*"
    r"0x18000,\s*0x20000,\s*0x40000,\s*0x100000\s*\}", runtime)
assert "__attribute__((noreturn))" in header
assert re.search(r"\b::_exit\s*\(", hotspot) is None
assert "orbis-jvm: native allocation failed" in debug
assert "requested=" in debug
assert "orbis-jvm: malloc failed" not in os_runtime
assert "mmap_control=" not in os_runtime
assert "orbis_direct_memory_allocate(outer_size)" in os_runtime
assert "orbis_direct_memory_set_user_ptr" in os_runtime
assert "orbis_direct_memory_find_user_ptr" in os_runtime
assert "orbis_direct_memory_release(old_outer_ptr)" in os_runtime
assert re.search(r"orbis_direct_memory_find_user_ptr\([\s\S]*?orbis_direct_memory_allocate\(new_outer_size\)[\s\S]*?memcpy\(direct_new_outer_ptr", os_runtime)
assert "sceKernelAllocateDirectMemory" in hotspot
assert "sceKernelMapDirectMemory" in hotspot
assert "sceKernelReleaseDirectMemory" in hotspot
assert "ORBIS_DIRECT_MEMORY_ALLOCATION_CAPACITY" in hotspot
assert "ORBIS_DIRECT_MEMORY_ARENA_CAPACITY" in hotspot
assert "ORBIS_DIRECT_MEMORY_ARENA_MIN_SIZE" in hotspot
assert "orbis_direct_memory_arena_allocate" in hotspot
assert "orbis_direct_memory_arena_release" in hotspot
assert "orbis_direct_memory_arena_is_empty" in hotspot
assert "orbis_direct_memory_allocate_standalone" in hotspot
assert "orbis-jvm: direct allocator stats" in hotspot
assert "orbis-jvm: direct malloc fallback requested=" not in os_runtime
assert re.search(r"if\s*\(result\s*!=\s*0\)", hotspot)
assert re.search(r"if\s*\(result\s*!=\s*0\s*\|\|\s*mapping\s*==\s*NULL\)", hotspot)
assert re.search(r"if\s*\(allocate_result\s*!=\s*0\)", runtime)
assert re.search(r"if\s*\(map_result\s*!=\s*0\s*\|\|\s*mapping\s*==\s*NULL\)", runtime)
assert re.search(
    r"direct_new_outer_ptr\s*=\s*orbis_direct_memory_allocate\(new_outer_size\);"
    r"[\s\S]*?if\s*\(direct_new_outer_ptr\s*==\s*NULL\)\s*\{\s*"
    r"return NULL;\s*// Preserve the old allocation on realloc failure\.",
    os_runtime)
assert re.search(
    r"void\s+os::free\(void\s*\*memblock\)[\s\S]*?"
    r"orbis_direct_memory_release\(old_outer_ptr\)[\s\S]*?::free\(old_outer_ptr\)",
    os_runtime)
assert re.search(
    r"sceKernelMapDirectMemory\([\s\S]*?"
    r"sceKernelReleaseDirectMemory\(direct_offset, mapped_size\)",
    hotspot)
print("Orbis runtime source tests passed")
