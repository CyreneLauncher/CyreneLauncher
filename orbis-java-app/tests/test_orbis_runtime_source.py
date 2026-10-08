#!/usr/bin/env python3
"""Contract tests for the shared Orbis runtime the eboot and application PRXs link."""

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
runtime = (ROOT / "tools" / "orbis_runtime.c").read_text()
header = (ROOT / "tools" / "orbis_runtime.h").read_text()

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
assert re.search(r"if\s*\(allocate_result\s*!=\s*0\)", runtime)
assert re.search(r"if\s*\(map_result\s*!=\s*0\s*\|\|\s*mapping\s*==\s*NULL\)", runtime)
print("Orbis runtime source tests passed")
