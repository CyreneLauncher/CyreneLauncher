/* SPDX-License-Identifier: ISC
 * Orbis back-end for dyncall's write/executable allocator (replaces dyncall_alloc_wx_mmap.c).
 *
 * Upstream maps one page per callback and mprotects it read/execute. On Orbis a page is 16 KiB
 * and anonymous mappings run out once the JVM, Mesa and the game hold their memory: Minecraft
 * creates a callback per texture on several worker threads and ~1600 of them failed with
 * "Failed to create the DCCallback object". Callbacks are tiny (DCCallback is 40 bytes on x64),
 * so serve them from read/write/execute chunks mapped while memory is still available (the first
 * callback is created with the GLFW window), the way HotSpot maps its code cache. Larger requests
 * keep upstream's map-then-protect behaviour.
 */
#include "dyncall_alloc_wx.h"

#include <orbis/libkernel.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/mman.h>

#define SLOT_SIZE 64
#define CHUNK_SIZE (1024 * 1024)
#define MAX_CHUNKS 16
#define PAGE_SIZE 0x4000

static char *chunks[MAX_CHUNKS];
static int chunk_count;
static void *free_slots;
static volatile int lock;

static void acquire(void) {
    while (__atomic_exchange_n(&lock, 1, __ATOMIC_ACQUIRE))
        sceKernelUsleep(0);
}

static void release(void) {
    __atomic_store_n(&lock, 0, __ATOMIC_RELEASE);
}

static int pooled(void *p) {
    for (int i = 0; i < chunk_count; ++i)
        if ((char *)p >= chunks[i] && (char *)p < chunks[i] + CHUNK_SIZE)
            return 1;
    return 0;
}

static void report(const char *what, int count) {
    char line[160];
    int length = snprintf(line, sizeof(line), "orbis-dyncall: %s (%d chunk(s) of %d KiB)\n",
                          what, count, CHUNK_SIZE / 1024);
    if (length > 0)
        sceKernelDebugOutText(0, line);
}

/* Called with the lock held. */
static int grow(void) {
    if (chunk_count == MAX_CHUNKS) {
        report("callback pool exhausted", chunk_count);
        return -1;
    }
    char *chunk = mmap(NULL, CHUNK_SIZE, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (chunk == MAP_FAILED) {
        report("cannot map another callback chunk", chunk_count);
        return -1;
    }
    for (size_t offset = CHUNK_SIZE; offset >= SLOT_SIZE; offset -= SLOT_SIZE) {
        void **slot = (void **)(chunk + offset - SLOT_SIZE);
        *slot = free_slots;
        free_slots = slot;
    }
    chunks[chunk_count++] = chunk;
    return 0;
}

DCerror dcAllocWX(size_t size, void **pp) {
    if (size <= SLOT_SIZE) {
        acquire();
        if (!free_slots && grow() != 0) {
            release();
            return -1;
        }
        void **slot = free_slots;
        free_slots = *slot;
        release();
        *pp = slot;
        return 0;
    }
    size_t length = (size + PAGE_SIZE - 1) & ~(size_t)(PAGE_SIZE - 1);
    void *p = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (p == MAP_FAILED)
        return -1;
    *pp = p;
    return 0;
}

DCerror dcInitExecWX(void *p, size_t size) {
    if (size <= SLOT_SIZE)
        return 0; /* pool chunks are already executable */
    size_t length = (size + PAGE_SIZE - 1) & ~(size_t)(PAGE_SIZE - 1);
    return mprotect(p, length, PROT_READ | PROT_EXEC);
}

void dcFreeWX(void *p, size_t size) {
    if (size <= SLOT_SIZE) {
        acquire();
        if (pooled(p)) {
            *(void **)p = free_slots;
            free_slots = p;
            release();
            return;
        }
        release();
    }
    munmap(p, (size + PAGE_SIZE - 1) & ~(size_t)(PAGE_SIZE - 1));
}
