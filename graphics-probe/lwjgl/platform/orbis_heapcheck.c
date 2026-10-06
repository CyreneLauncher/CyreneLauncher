/* ORBIS_HEAP_CHECK: a guarded heap for the platform module, built only on request
 * (ORBIS_HEAP_CHECK=1 build_platform_prx.py), to catch heap corruption where it is made rather than where
 * musl's malloc later trips over it.
 *
 * Linked with --wrap for malloc, free, calloc, realloc, memalign and posix_memalign, so every allocation in
 * the module - Mesa's included - gets a header (size, the code addresses found on the allocating stack, a
 * magic word) and a canary after the payload. free checks both; a freed block is poisoned and held in a
 * quarantine, and the poison is checked when it leaves, which catches writes after free. The first defect
 * is written to /data/orbis-jdk/runner/tmp/heapcheck.log with everything known about the block, then the
 * process traps on purpose so hs_err shows this file's frame.
 *
 * Nothing here may allocate: the report formats into a stack buffer and goes out with open/write. */
#include <fcntl.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

void *__real_malloc(size_t n);
void __real_free(void *p);

extern char __text_start[], __eh_frame_start[];
int sceKernelDebugOutText(int channel, const char *text);
int sceKernelUsleep(unsigned int microseconds);

#define MAGIC 0x4f524249534845A1ull /* "ORBISHE" + tag */
#define FREED 0x4f52424953464Eull
#define CANARY 0xC3u
#define CANARY_BYTES 16
#define POISON 0xDFu
#define CALLERS 4

struct hdr {
   void *base;              /* what __real_malloc returned */
   size_t size;             /* payload bytes */
   uintptr_t alloc[CALLERS]; /* code addresses on the allocating stack, innermost first */
   uintptr_t freer;
   uint64_t magic;
   uint8_t front[16];       /* right before the payload: catches writes just BELOW a block, where musl keeps its
                             * chunk header - the field the crashes find overwritten */
};
_Static_assert(sizeof(struct hdr) % 16 == 0, "payload alignment");

/* ------------------------------------------------------------------ report */

static int report_fd = -1;

static void say(const char *fmt, ...) {
   char line[512];
   va_list ap;
   va_start(ap, fmt);
   int n = vsnprintf(line, sizeof(line) - 1, fmt, ap);
   va_end(ap);
   if (n < 0) return;
   if (n > (int)sizeof(line) - 2) n = sizeof(line) - 2;
   line[n++] = '\n';
   line[n] = 0;
   if (report_fd < 0)
      report_fd = open("/data/orbis-jdk/runner/tmp/heapcheck.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
   if (report_fd >= 0) write(report_fd, line, n);
   sceKernelDebugOutText(0, line);
}

static void dump(const char *what, const void *at, size_t len) {
   const uint8_t *p = at;
   for (size_t off = 0; off < len; off += 32) {
      char hex[3 * 32 + 1];
      size_t k = 0;
      for (size_t i = 0; i < 32 && off + i < len; i++)
         k += snprintf(hex + k, sizeof(hex) - k, "%02x ", p[off + i]);
      say("heapcheck:   %s %p+%3zu: %s", what, at, off, hex);
   }
}

static void describe(const struct hdr *h) {
   say("heapcheck:   block %p, %zu bytes, allocated from text offsets %#lx %#lx %#lx %#lx, freed from %#lx",
       (const void *)(h + 1), h->size,
       (unsigned long)(h->alloc[0] ? h->alloc[0] - (uintptr_t)__text_start : 0),
       (unsigned long)(h->alloc[1] ? h->alloc[1] - (uintptr_t)__text_start : 0),
       (unsigned long)(h->alloc[2] ? h->alloc[2] - (uintptr_t)__text_start : 0),
       (unsigned long)(h->alloc[3] ? h->alloc[3] - (uintptr_t)__text_start : 0),
       (unsigned long)(h->freer ? h->freer - (uintptr_t)__text_start : 0));
}

__attribute__((noreturn)) static void fail(void) {
   say("heapcheck: text starts at %p; offsets above are relative to it (nm addresses). Trapping.",
       (void *)__text_start);
   __builtin_trap();
}

/* ------------------------------------------------------------------ helpers */

static inline int in_text(uintptr_t a) {
   return a >= (uintptr_t)__text_start && a < (uintptr_t)__eh_frame_start;
}

/* The callers that matter are usually several frames up (ralloc, blob, util wrappers sit in between), and
 * the module is not built with frame pointers - so take the first code addresses found on the stack. A
 * heuristic: a stale word can show up, the real frames always do. */
__attribute__((noinline)) static void callers(uintptr_t first, uintptr_t out[CALLERS]) {
   uintptr_t *sp = (uintptr_t *)__builtin_frame_address(0);
   unsigned found = 0;
   out[found++] = first;
   for (unsigned i = 0; i < 48 && found < CALLERS; i++) {
      const uintptr_t w = sp[i];
      if (in_text(w) && w != out[found - 1])
         out[found++] = w;
   }
   while (found < CALLERS) out[found++] = 0;
}

static inline uint8_t *payload(struct hdr *h) { return (uint8_t *)(h + 1); }

static void *finish(void *base, uint8_t *pay, size_t n, uintptr_t ret) {
   struct hdr *h = (struct hdr *)pay - 1;
   h->base = base;
   h->size = n;
   callers(ret, h->alloc);
   h->freer = 0;
   h->magic = MAGIC;
   memset(h->front, CANARY, sizeof(h->front));
   memset(pay + n, CANARY, CANARY_BYTES);
   return pay;
}

/* The guards on both sides of a block, live or freed. */
static void guards(struct hdr *h, const char *op, const void *p, uintptr_t ret) {
   for (unsigned i = 0; i < sizeof(h->front); i++) {
      if (h->front[i] != CANARY) {
         say("heapcheck: %s(%p) from text %#lx: UNDERFLOW - written below the start of a %zu-byte %s block "
             "(front guard byte %u of %zu)", op, p, (unsigned long)(ret ? ret - (uintptr_t)__text_start : 0),
             h->size, h->magic == FREED ? "FREED" : "live", i, sizeof(h->front));
         describe(h);
         dump("front", (const uint8_t *)p - 64, 96);
         fail();
      }
   }
   const uint8_t *c = payload(h) + h->size;
   for (unsigned i = 0; i < CANARY_BYTES; i++) {
      if (c[i] != CANARY) {
         say("heapcheck: %s(%p) from text %#lx: OVERFLOW - %zu-byte %s block written past its end (canary byte %u)",
             op, p, (unsigned long)(ret ? ret - (uintptr_t)__text_start : 0), h->size,
             h->magic == FREED ? "FREED" : "live", i);
         describe(h);
         dump("tail", payload(h) + (h->size > 64 ? h->size - 64 : 0), (h->size > 64 ? 64 : h->size) + CANARY_BYTES);
         fail();
      }
   }
}

static struct hdr *checked(void *p, const char *op, uintptr_t ret) {
   struct hdr *h = (struct hdr *)p - 1;
   if (h->magic != MAGIC) {
      say("heapcheck: %s(%p) from text %#lx: header magic is %#llx - %s", op, p,
          (unsigned long)(ret - (uintptr_t)__text_start), (unsigned long long)h->magic,
          h->magic == FREED ? "DOUBLE FREE (block already freed)" : "header overwritten, or not our pointer");
      if (h->magic == FREED) describe(h);
      dump("before", (uint8_t *)p - 96, 96);
      fail();
   }
   guards(h, op, p, ret);
   return h;
}

/* ------------------------------------------------------------------ quarantine */

#define QUARANTINE_SLOTS 16384
#define QUARANTINE_BYTES (64u << 20)
#define POISON_CHECK_MAX (16u << 20) /* bytes of each freed block poisoned and checked: all of any real block */

static struct hdr *quarantine[QUARANTINE_SLOTS];
static unsigned q_head, q_count;
static size_t q_bytes;
static volatile int q_lock;

static void lock(void) {
   while (__atomic_exchange_n(&q_lock, 1, __ATOMIC_ACQUIRE)) sceKernelUsleep(0);
}
static void unlock(void) { __atomic_store_n(&q_lock, 0, __ATOMIC_RELEASE); }

static void release(struct hdr *h) {
   if (h->magic != FREED) {
      say("heapcheck: a quarantined block's header changed while it was free (magic %#llx)",
          (unsigned long long)h->magic);
      describe(h);
      dump("header", (const uint8_t *)h, sizeof(*h));
      fail();
   }
   guards(h, "release", payload(h), 0);
   const size_t n = h->size < POISON_CHECK_MAX ? h->size : POISON_CHECK_MAX;
   const uint8_t *p = payload(h);
   for (size_t i = 0; i < n; i++) {
      if (p[i] != POISON) {
         say("heapcheck: WRITE AFTER FREE - byte %zu of a freed %zu-byte block changed to %#x", i, h->size, p[i]);
         describe(h);
         dump("freed", p + (i >= 32 ? i - 32 : 0), 96);
         fail();
      }
   }
   __real_free(h->base);
}

static void retire(struct hdr *h, uintptr_t ret) {
   h->magic = FREED;
   h->freer = ret;
#ifdef HEAPCHECK_NO_QUARANTINE
   /* ORBIS_HEAP_CHECK=2: guards only, freed blocks go straight back - whether the delay hides the defect. */
   __real_free(h->base);
   return;
#endif
   memset(payload(h), POISON, h->size < POISON_CHECK_MAX ? h->size : POISON_CHECK_MAX);
   struct hdr *out[64];
   unsigned n_out = 0;
   lock();
   if (q_count == QUARANTINE_SLOTS || q_bytes > QUARANTINE_BYTES) {
      /* Evict a few at a time; checked outside the lock. */
      while (q_count > 0 && n_out < 64 && (q_count == QUARANTINE_SLOTS || q_bytes > QUARANTINE_BYTES * 3 / 4)) {
         const unsigned tail = (q_head + QUARANTINE_SLOTS - q_count) % QUARANTINE_SLOTS;
         out[n_out++] = quarantine[tail];
         q_bytes -= quarantine[tail]->size;
         q_count--;
      }
   }
   quarantine[q_head] = h;
   q_head = (q_head + 1) % QUARANTINE_SLOTS;
   q_count++;
   q_bytes += h->size;
   unlock();
   for (unsigned i = 0; i < n_out; i++) release(out[i]);
}

/* ------------------------------------------------------------------ the wrapped API */

static void *alloc_aligned(size_t align, size_t n, uintptr_t ret) {
   if (n > SIZE_MAX - 256 - align) return NULL;
   uint8_t *base = __real_malloc(sizeof(struct hdr) + n + CANARY_BYTES + align);
   if (!base) return NULL;
   uintptr_t pay = (uintptr_t)base + sizeof(struct hdr);
   pay = (pay + align - 1) & ~(uintptr_t)(align - 1);
   return finish(base, (uint8_t *)pay, n, ret);
}

void *__wrap_malloc(size_t n) {
   return alloc_aligned(16, n, (uintptr_t)__builtin_return_address(0));
}

void __wrap_free(void *p) {
   if (!p) return;
   const uintptr_t ret = (uintptr_t)__builtin_return_address(0);
   retire(checked(p, "free", ret), ret);
}

void *__wrap_calloc(size_t a, size_t b) {
   if (b && a > SIZE_MAX / b) return NULL;
   void *p = alloc_aligned(16, a * b, (uintptr_t)__builtin_return_address(0));
   if (p) memset(p, 0, a * b);
   return p;
}

void *__wrap_realloc(void *p, size_t n) {
   const uintptr_t ret = (uintptr_t)__builtin_return_address(0);
   if (!p) return alloc_aligned(16, n, ret);
   struct hdr *h = checked(p, "realloc", ret);
   void *q = alloc_aligned(16, n, ret);
   if (!q) return NULL;
   memcpy(q, p, h->size < n ? h->size : n);
   retire(h, ret);
   return q;
}

void *__wrap_memalign(size_t align, size_t n) {
   if (align < 16) align = 16;
   if (align & (align - 1)) return NULL;
   return alloc_aligned(align, n, (uintptr_t)__builtin_return_address(0));
}

int __wrap_posix_memalign(void **out, size_t align, size_t n) {
   if (align < sizeof(void *) || (align & (align - 1))) return 22; /* EINVAL */
   void *p = alloc_aligned(align < 16 ? 16 : align, n, (uintptr_t)__builtin_return_address(0));
   if (!p) return 12; /* ENOMEM */
   *out = p;
   return 0;
}
