/* SPDX-License-Identifier: MIT
 * Orbis closure allocator for libffi (replaces closures.c).
 *
 * Upstream either mallocs closures (no executable heap here) or double-maps a temporary file,
 * which the console does not offer. LWJGL creates a closure per callback, so serve them from
 * read/write/execute chunks mapped while memory is still available, the way the dyncall build
 * serves DCCallbacks (lwjgl/dyncall/orbis_dyncall_alloc_wx.c): an anonymous mapping costs 16 KiB
 * and runs out once the JVM, Mesa and the game hold their memory. The code address of a closure
 * is its data address. Larger requests get their own read/write/execute mapping.
 */
#include <fficonfig.h>
#include <ffi.h>
#include <ffi_common.h>

#include <orbis/libkernel.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/mman.h>

#define SLOT_SIZE 64
#define CHUNK_SIZE (1024 * 1024)
#define MAX_CHUNKS 16
#define MAP_GRANULE 0x4000 /* the console page; limits.h says 4096 */
#define HEADER 16 /* a large closure's mapping length, kept ahead of it */

_Static_assert(sizeof(ffi_closure) <= SLOT_SIZE, "an ffi_closure must fit a pool slot");

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
    int length = snprintf(line, sizeof(line), "orbis-ffi: %s (%d chunk(s) of %d KiB)\n",
                          what, count, CHUNK_SIZE / 1024);
    if (length > 0)
        sceKernelDebugOutText(0, line);
}

/* Called with the lock held. */
static int grow(void) {
    if (chunk_count == MAX_CHUNKS) {
        report("closure pool exhausted", chunk_count);
        return -1;
    }
    char *chunk = mmap(NULL, CHUNK_SIZE, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (chunk == MAP_FAILED) {
        report("cannot map another closure chunk", chunk_count);
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

void *ffi_closure_alloc(size_t size, void **code) {
    if (!code)
        return NULL;
    void *p;
    if (size <= SLOT_SIZE) {
        acquire();
        if (!free_slots && grow() != 0) {
            release();
            return NULL;
        }
        void **slot = free_slots;
        free_slots = *slot;
        release();
        p = slot;
    } else {
        size_t length = (size + HEADER + MAP_GRANULE - 1) & ~(size_t)(MAP_GRANULE - 1);
        char *map = mmap(NULL, length, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANON, -1, 0);
        if (map == MAP_FAILED)
            return NULL;
        *(size_t *)map = length;
        p = map + HEADER;
    }
    return *code = p;
}

void ffi_closure_free(void *ptr) {
    if (!ptr)
        return;
    acquire();
    if (pooled(ptr)) {
        *(void **)ptr = free_slots;
        free_slots = ptr;
        release();
        return;
    }
    release();
    char *map = (char *)ptr - HEADER;
    munmap(map, *(size_t *)map);
}

void *ffi_data_to_code_pointer(void *data) {
    return data;
}

int ffi_tramp_is_present(void *ptr) {
    (void)ptr;
    return 0;
}
