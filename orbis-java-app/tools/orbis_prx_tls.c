/*
 * Per-thread TLS for Orbis PRXs that need real thread-local state.
 *
 * The loader exposes no TLS block for user PRXs (see orbis_tls.c). Code compiled
 * with -fPIC into a -shared -Bsymbolic link reaches every thread-local through
 * __tls_get_addr with {module, offset}; the offset is resolved at link time
 * relative to the start of this PRX's TLS image. This replacement keeps one
 * block per thread, initialised from that image.
 *
 * Blocks are found in a table keyed by pthread_self(), not through a pthread key:
 * libkernel backs pthread_setspecific with a small internal pool charged per thread
 * that holds any value, and once every JVM thread held its block there (JDK 25's
 * Thread::current is a thread-local), a burst of new threads - Minecraft restarting
 * its workers for a resource reload - ended in "Internal Memory for setspecific is
 * running out" and an abort. The graphics modules (LWJGL, stb, FreeType, OpenAL, Mesa)
 * kept one more value per thread each: every worker that decoded a texture held one,
 * and Mesa's shader compiler hit the same abort at startup. Without a key there is no destructor, so the module
 * releases a thread's block itself: modules link with --wrap=pthread_create, so a
 * thread the module starts (Mesa's queues, OpenAL's mixer) releases its block when its
 * start routine returns. A thread started elsewhere (a Java thread calling in) keeps
 * its block, which stays with its pthread_t and is reused by the next thread libkernel
 * gives that pthread_t to; these modules' thread-locals of such threads hold no
 * per-thread resources (musl's locale, stb's failure reason, GL's current context
 * of the render thread).
 *
 * Only the owning thread writes its entry, so a slot is claimed with one CAS and
 * read without one; a release leaves a tombstone that later claims reuse, so the
 * table stays bounded by the peak number of threads holding blocks.
 *
 * Link with --wrap=pthread_create and the PRX script that collects .tdata.* and .tbss.* into .tls and
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

/*
 * With no initialised thread-local in a module, lld emits .tls as NOBITS and the
 * image bounds would cover unrelated bytes. This anchor keeps .tls PROGBITS, so the
 * merged .tbss is zero-filled in the image every block is copied from.
 */
__attribute__((used, visibility("hidden"))) __thread unsigned char orbis_prx_tls_anchor = 1;

#define ORBIS_PRX_TLS_SLOTS 4096u      /* power of two */
#define TLS_EMPTY ((uintptr_t)0)
#define TLS_TOMBSTONE ((uintptr_t)1)
#define TLS_CLAIMING ((uintptr_t)2)     /* being filled in by the thread that claimed it */

struct tls_slot {
    uintptr_t key;                      /* pthread_self(), or EMPTY / TOMBSTONE / CLAIMING */
    unsigned char *block;
};

__attribute__((visibility("hidden"))) struct tls_slot orbis_prx_tls_slots[ORBIS_PRX_TLS_SLOTS];
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

static void tls_setup(void) {
    pthread_mutex_lock(&tls_setup_lock);
    if (!__atomic_load_n(&tls_ready, __ATOMIC_ACQUIRE)) {
        orbis_prx_tls_template(&tls_image, &tls_file_size, &tls_memory_size);
        if (tls_file_size > tls_memory_size) tls_fail("template file size exceeds memory size");
        __atomic_store_n(&tls_ready, 1, __ATOMIC_RELEASE);
    }
    pthread_mutex_unlock(&tls_setup_lock);
}

static inline uintptr_t tls_self(void) {
    return (uintptr_t)pthread_self();
}

static inline size_t tls_home(uintptr_t key) {
    return (size_t)(((uint64_t)key * 0x9E3779B97F4A7C15ull) >> 52) & (ORBIS_PRX_TLS_SLOTS - 1);
}

/* The calling thread's slot, or NULL. */
static struct tls_slot *tls_find(uintptr_t self) {
    size_t i = tls_home(self);
    for (size_t n = 0; n < ORBIS_PRX_TLS_SLOTS; n++, i = (i + 1) & (ORBIS_PRX_TLS_SLOTS - 1)) {
        uintptr_t key = __atomic_load_n(&orbis_prx_tls_slots[i].key, __ATOMIC_ACQUIRE);
        if (key == self) return &orbis_prx_tls_slots[i];
        if (key == TLS_EMPTY) return NULL;
    }
    return NULL;
}

static unsigned char *tls_block_create(uintptr_t self) {
    void *block = NULL;
    size_t size = tls_memory_size ? tls_memory_size : 1;
    if (posix_memalign(&block, ORBIS_PRX_TLS_ALIGNMENT, size) != 0 || block == NULL)
        tls_fail("thread block allocation failed");
    memcpy(block, tls_image, tls_file_size);
    memset((unsigned char *)block + tls_file_size, 0, size - tls_file_size);
    /* Claim the first tombstone or empty slot on this thread's probe path. */
    size_t i = tls_home(self);
    for (size_t n = 0; n < ORBIS_PRX_TLS_SLOTS; n++, i = (i + 1) & (ORBIS_PRX_TLS_SLOTS - 1)) {
        uintptr_t key = __atomic_load_n(&orbis_prx_tls_slots[i].key, __ATOMIC_ACQUIRE);
        if ((key == TLS_EMPTY || key == TLS_TOMBSTONE) &&
            __atomic_compare_exchange_n(&orbis_prx_tls_slots[i].key, &key, TLS_CLAIMING, 0,
                                        __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
            orbis_prx_tls_slots[i].block = (unsigned char *)block;
            __atomic_store_n(&orbis_prx_tls_slots[i].key, self, __ATOMIC_RELEASE);
#ifdef ORBIS_PRX_TLS_EXTERNAL_TEMPLATE
            __atomic_fetch_add(&tls_live_blocks, 1, __ATOMIC_ACQ_REL);
#endif
            return (unsigned char *)block;
        }
    }
    tls_fail("thread table is full");
}

/* Releases the calling thread's block; a later thread-local access starts a fresh one. */
__attribute__((visibility("hidden")))
void orbis_prx_tls_thread_exit(void) {
    struct tls_slot *slot = tls_find(tls_self());
    if (slot == NULL) return;
    unsigned char *block = slot->block;
    slot->block = NULL;
    __atomic_store_n(&slot->key, TLS_TOMBSTONE, __ATOMIC_RELEASE);
    free(block);
#ifdef ORBIS_PRX_TLS_EXTERNAL_TEMPLATE
    __atomic_fetch_sub(&tls_live_blocks, 1, __ATOMIC_ACQ_REL);
#endif
}

__attribute__((visibility("hidden")))
void *__tls_get_addr(const uintptr_t descriptor[2]) {
    if (!__atomic_load_n(&tls_ready, __ATOMIC_ACQUIRE)) tls_setup();
    uintptr_t self = tls_self();
    struct tls_slot *slot = tls_find(self);
    unsigned char *block = slot != NULL ? slot->block : tls_block_create(self);
    /* General dynamic passes the variable's offset; local dynamic passes 0. */
    if (descriptor[1] > tls_memory_size) tls_fail("offset outside the TLS image");
    return block + descriptor[1];
}

/* Threads this module starts release their block when their start routine returns. */
struct tls_thread_start {
    void *(*routine)(void *);
    void *argument;
};

int __real_pthread_create(pthread_t *thread, const pthread_attr_t *attributes,
                          void *(*routine)(void *), void *argument);

static void *tls_thread_start(void *start) {
    struct tls_thread_start call = *(struct tls_thread_start *)start;
    free(start);
    void *result = call.routine(call.argument);
    orbis_prx_tls_thread_exit();
    return result;
}

__attribute__((visibility("hidden")))
int __wrap_pthread_create(pthread_t *thread, const pthread_attr_t *attributes,
                          void *(*routine)(void *), void *argument) {
    struct tls_thread_start *start = (struct tls_thread_start *)malloc(sizeof(*start));
    if (start == NULL) return __real_pthread_create(thread, attributes, routine, argument);
    start->routine = routine;
    start->argument = argument;
    int result = __real_pthread_create(thread, attributes, tls_thread_start, start);
    if (result != 0) free(start);
    return result;
}
