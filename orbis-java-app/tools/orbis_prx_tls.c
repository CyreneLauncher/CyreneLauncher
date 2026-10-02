/*
 * Per-thread TLS for Orbis PRXs that need real thread-local state.
 *
 * The loader exposes no TLS block for user PRXs (see orbis_tls.c). Code compiled
 * with -fPIC into a -shared -Bsymbolic link reaches every thread-local through
 * __tls_get_addr with {module, offset}; the offset is resolved at link time
 * relative to the start of this PRX's TLS image. This replacement keeps one
 * block per thread, initialised from that image and released at thread exit.
 *
 * Link with the PRX script that collects .tdata.* and .tbss.* into .tls and
 * defines __orbis_tls_image_start/__orbis_tls_image_end around it. Blocks are
 * aligned to ORBIS_PRX_TLS_ALIGNMENT; the build audit rejects a larger PT_TLS
 * alignment. Use instead of orbis_tls.c, never together.
 */
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define ORBIS_PRX_TLS_ALIGNMENT 64

__attribute__((weak, visibility("hidden")))
extern void orbis_debug_boundary(const char *text);

#ifdef ORBIS_PRX_TLS_EXTERNAL_TEMPLATE
/* Host tests supply the image of the module under test. */
__attribute__((visibility("hidden")))
void orbis_prx_tls_template(const unsigned char **image, size_t *file_size, size_t *memory_size);
#else
__attribute__((visibility("hidden"))) extern const unsigned char __orbis_tls_image_start[];
__attribute__((visibility("hidden"))) extern const unsigned char __orbis_tls_image_end[];

static void orbis_prx_tls_template(const unsigned char **image, size_t *file_size,
                                   size_t *memory_size) {
    /* The PRX script merges .tbss into .tls, so the whole image is initialised. */
    *image = __orbis_tls_image_start;
    *file_size = *memory_size = (size_t)(__orbis_tls_image_end - __orbis_tls_image_start);
}
#endif

static pthread_key_t tls_key;
static pthread_mutex_t tls_setup_lock = PTHREAD_MUTEX_INITIALIZER;
static int tls_ready;
static const unsigned char *tls_image;
static size_t tls_file_size;
static size_t tls_memory_size;
#ifdef ORBIS_PRX_TLS_EXTERNAL_TEMPLATE
static long tls_live_blocks;
__attribute__((visibility("default"))) long orbis_prx_tls_live_blocks(void) {
    return __atomic_load_n(&tls_live_blocks, __ATOMIC_ACQUIRE);
}
#endif

__attribute__((noreturn)) static void tls_fail(const char *detail) {
    if (orbis_debug_boundary) {
        orbis_debug_boundary("orbis-prx-tls: ");
        orbis_debug_boundary(detail);
        orbis_debug_boundary("\n");
    }
    abort();
}

static void tls_release(void *block) {
    free(block);
#ifdef ORBIS_PRX_TLS_EXTERNAL_TEMPLATE
    __atomic_fetch_sub(&tls_live_blocks, 1, __ATOMIC_ACQ_REL);
#endif
}

static void tls_setup(void) {
    pthread_mutex_lock(&tls_setup_lock);
    if (!__atomic_load_n(&tls_ready, __ATOMIC_ACQUIRE)) {
        orbis_prx_tls_template(&tls_image, &tls_file_size, &tls_memory_size);
        if (tls_file_size > tls_memory_size) tls_fail("template file size exceeds memory size");
        if (pthread_key_create(&tls_key, tls_release) != 0) tls_fail("pthread_key_create failed");
        __atomic_store_n(&tls_ready, 1, __ATOMIC_RELEASE);
    }
    pthread_mutex_unlock(&tls_setup_lock);
}

static unsigned char *tls_block_create(void) {
    void *block = NULL;
    size_t size = tls_memory_size ? tls_memory_size : 1;
    if (posix_memalign(&block, ORBIS_PRX_TLS_ALIGNMENT, size) != 0 || block == NULL)
        tls_fail("thread block allocation failed");
    memcpy(block, tls_image, tls_file_size);
    memset((unsigned char *)block + tls_file_size, 0, size - tls_file_size);
    if (pthread_setspecific(tls_key, block) != 0) {
        free(block);
        tls_fail("pthread_setspecific failed");
    }
#ifdef ORBIS_PRX_TLS_EXTERNAL_TEMPLATE
    __atomic_fetch_add(&tls_live_blocks, 1, __ATOMIC_ACQ_REL);
#endif
    return (unsigned char *)block;
}

__attribute__((visibility("hidden")))
void *__tls_get_addr(const uintptr_t descriptor[2]) {
    if (!__atomic_load_n(&tls_ready, __ATOMIC_ACQUIRE)) tls_setup();
    unsigned char *block = (unsigned char *)pthread_getspecific(tls_key);
    if (block == NULL) block = tls_block_create();
    /* General dynamic passes the variable's offset; local dynamic passes 0. */
    if (descriptor[1] > tls_memory_size) tls_fail("offset outside the TLS image");
    return block + descriptor[1];
}
