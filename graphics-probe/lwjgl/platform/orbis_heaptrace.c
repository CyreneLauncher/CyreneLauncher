/* ORBIS_HEAP_CHECK=trace: watch musl's own heap without changing it.
 *
 * The guarded heap (orbis_heapcheck.c) made the corruption disappear - by replacing musl's allocator it
 * replaces whatever goes wrong with it. This build changes nothing about allocation: malloc, free, calloc and
 * realloc pass straight through, and each call is recorded in a ring (operation, pointer, size, result, the
 * code addresses on the calling stack). After every call the head of each of musl's 64 bins is checked: a
 * free chunk at the head of a bin must have both links, and the crashes all find next == NULL there.
 *
 * The first broken head is reported to /data/orbis-jdk/runner/tmp/heaptrace.log with the ring's history of
 * that address - which allocations held it, who freed them - and the last calls, then the process traps.
 *
 * musl's `mal` is static; the build links twice and passes its address in as orbis_musl_mal (--defsym). */
#include <fcntl.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

void *__real_malloc(size_t n);
void __real_free(void *p);
void *__real_calloc(size_t a, size_t b);
void *__real_realloc(void *p, size_t n);

extern char __text_start[], __eh_frame_start[];
extern char orbis_musl_mal[];
int sceKernelDebugOutText(int channel, const char *text);
int sceKernelUsleep(unsigned int microseconds);

/* musl oldmalloc's layout, as its malloc.c declares it (and as the disassembly addresses it). */
struct chunk { size_t psize, csize; struct chunk *next, *prev; };
struct bin { volatile int lock[2]; struct chunk *head, *tail; };
struct mal { volatile uint64_t binmap; struct bin bins[64]; };
#define BIN_TO_CHUNK(b) ((struct chunk *)((char *)&(b)->head - offsetof(struct chunk, next)))

/* ------------------------------------------------------------------ the ring */

enum { OP_MALLOC = 1, OP_FREE, OP_CALLOC, OP_REALLOC };
static const char *const op_name[] = {"?", "malloc", "free", "calloc", "realloc"};

#define CALLERS 3
struct rec {
   uint32_t op, seq;
   uintptr_t ptr;    /* argument (free/realloc) */
   size_t size;
   uintptr_t result; /* returned pointer */
   uintptr_t caller[CALLERS];
};
#define RING (1u << 17)
static struct rec ring[RING];
static volatile uint32_t ring_next;

static inline int in_text(uintptr_t a) {
   return a >= (uintptr_t)__text_start && a < (uintptr_t)__eh_frame_start;
}

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

static void record(uint32_t op, uintptr_t ptr, size_t size, uintptr_t result, uintptr_t ret) {
   const uint32_t seq = __atomic_fetch_add(&ring_next, 1, __ATOMIC_RELAXED);
   struct rec *r = &ring[seq % RING];
   r->op = 0;
   r->seq = seq;
   r->ptr = ptr;
   r->size = size;
   r->result = result;
   callers(ret, r->caller);
   __atomic_store_n(&r->op, op, __ATOMIC_RELEASE);
}

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
      report_fd = open("/data/orbis-jdk/runner/tmp/heaptrace.log", O_WRONLY | O_CREAT | O_TRUNC, 0644);
   if (report_fd >= 0) write(report_fd, line, n);
}

static unsigned long off(uintptr_t a) { return a ? (unsigned long)(a - (uintptr_t)__text_start) : 0; }

static void print(const struct rec *r) {
   say("  #%u %-7s ptr %#lx size %zu -> %#lx  from %#lx %#lx %#lx", r->seq, op_name[r->op <= 4 ? r->op : 0],
       (unsigned long)r->ptr, r->size, (unsigned long)r->result, off(r->caller[0]), off(r->caller[1]),
       off(r->caller[2]));
}

static volatile int reported;

__attribute__((noreturn)) static void report(unsigned bin, const struct chunk *c) {
   if (__atomic_exchange_n(&reported, 1, __ATOMIC_ACQ_REL)) for (;;) sceKernelUsleep(1000000);
   const uintptr_t at = (uintptr_t)c, mem = at + 16;
   say("heaptrace: bin %u's head chunk %#lx (memory %#lx) is broken: psize %#zx csize %#zx next %p prev %p",
       bin, (unsigned long)at, (unsigned long)mem, c->psize, c->csize, (void *)c->next, (void *)c->prev);
   say("heaptrace: text starts at %p; 'from' values are offsets from it (nm addresses). %u calls recorded.",
       (void *)__text_start, ring_next);
   const uint32_t last = ring_next;
   const uint32_t first = last > RING ? last - RING : 0;
   say("heaptrace: every recorded call whose block contains or adjoins that chunk (oldest first):");
   unsigned shown = 0;
   for (uint32_t s = first; s < last && shown < 400; s++) {
      const struct rec *r = &ring[s % RING];
      if (!r->op || r->seq != s) continue;
      const uintptr_t lo = r->result ? r->result : r->ptr;
      const size_t sz = r->size;
      const int touches = (lo && at + 64 > lo - 64 && at < lo + sz + 64) ||
                          (r->ptr && at + 64 > r->ptr - 64 && at < r->ptr + 64);
      if (touches) { print(r); shown++; }
   }
   say("heaptrace: the last 200 calls:");
   for (uint32_t s = last > 200 ? last - 200 : 0; s < last; s++) {
      const struct rec *r = &ring[s % RING];
      if (r->op && r->seq == s) print(r);
   }
   say("heaptrace: trapping.");
   __builtin_trap();
}

static void check(void) {
   struct mal *m = (struct mal *)orbis_musl_mal;
   for (unsigned j = 0; j < 64; j++) {
      struct bin *b = &m->bins[j];
      struct chunk *h = b->head;
      if (!h || h == BIN_TO_CHUNK(b)) continue;
      if (h->next && h->prev) continue;
      /* A head read while another thread unbins it can be user memory for a moment: look again. */
      sceKernelUsleep(2000);
      if (b->head == h && (!h->next || !h->prev)) report(j, h);
   }
}

/* ------------------------------------------------------------------ the wrapped API */

void *__wrap_malloc(size_t n) {
   void *p = __real_malloc(n);
   record(OP_MALLOC, 0, n, (uintptr_t)p, (uintptr_t)__builtin_return_address(0));
   check();
   return p;
}

void __wrap_free(void *p) {
   size_t size = 0;
   if (p) {
      const char *q = p;
      __asm__("" : "+r"(q)); /* musl's header sits before the block; keep clang from treating the read as UB */
      size = (*(const size_t *)(q - 8) & ~(size_t)1) - 16;
   }
   record(OP_FREE, (uintptr_t)p, size, 0, (uintptr_t)__builtin_return_address(0));
   __real_free(p);
   check();
}

void *__wrap_calloc(size_t a, size_t b) {
   void *p = __real_calloc(a, b);
   record(OP_CALLOC, 0, a * b, (uintptr_t)p, (uintptr_t)__builtin_return_address(0));
   check();
   return p;
}

void *__wrap_realloc(void *p, size_t n) {
   void *q = __real_realloc(p, n);
   record(OP_REALLOC, (uintptr_t)p, n, (uintptr_t)q, (uintptr_t)__builtin_return_address(0));
   check();
   return q;
}
