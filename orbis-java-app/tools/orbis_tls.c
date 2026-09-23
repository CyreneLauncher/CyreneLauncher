/*
 * OpenOrbis PRX TLS adapter.
 *
 * The loader exposes an empty PT_TLS segment for user PRXs. The JDK's
 * bundled libc nevertheless emits a TLS descriptor for __musl_current_locale.
 * Keep a stable fallback block until per-thread PRX TLS is available.
 */
#include <stdint.h>
#include <stddef.h>

static unsigned char orbis_tls_block[256] __attribute__((aligned(16)));

void *__tls_get_addr(const uintptr_t descriptor[2]) {
    uintptr_t offset = descriptor[1];
    if (offset >= sizeof(orbis_tls_block)) {
        return NULL;
    }
    return orbis_tls_block + offset;
}
