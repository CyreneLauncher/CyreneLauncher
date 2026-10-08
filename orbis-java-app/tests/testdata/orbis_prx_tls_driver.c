/* Drives orbis_prx_tls_module: per-thread copies, template initialisation, release on thread exit,
 * a churn of threads far beyond the table size (released slots are reused), and threads the module
 * starts itself releasing their blocks without a call (wrapped pthread_create). */
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int *module_counter(void);
char *module_name(void);
long *module_zero(void);
int *module_local(void);
long orbis_prx_tls_live_blocks(void);
void module_thread_exit(void);
int module_spawn_and_join(long value);

#define THREADS 8
static pthread_barrier_t written;
static void *addresses[THREADS];

#define CHECK(condition) do { if (!(condition)) { \
    fprintf(stderr, "check failed line %d: %s\n", __LINE__, #condition); exit(1); } } while (0)

static void check_template(void) {
    CHECK(*module_counter() == 41);
    CHECK(strcmp(module_name(), "template") == 0);
    for (int i = 0; i < 32; ++i) CHECK(module_zero()[i] == 0);
    CHECK(*module_local() == 5);
}

static void *worker(void *argument) {
    int index = (int)(long)argument;
    check_template();
    *module_counter() = 1000 + index;
    snprintf(module_name(), 64, "thread-%d", index);
    module_zero()[31] = index;
    *module_local() = -index;
    addresses[index] = module_counter();
    pthread_barrier_wait(&written);
    char expected[64];
    snprintf(expected, sizeof(expected), "thread-%d", index);
    CHECK(*module_counter() == 1000 + index);
    CHECK(strcmp(module_name(), expected) == 0);
    CHECK(module_zero()[31] == index);
    CHECK(*module_local() == -index);
    module_thread_exit();
    return NULL;
}

#define CHURN 20000
static void *short_lived(void *argument) {
    check_template();
    *module_counter() = (int)(long)argument;
    module_thread_exit();
    return NULL;
}

int main(void) {
    CHECK(orbis_prx_tls_live_blocks() == 0);
    check_template();
    CHECK(orbis_prx_tls_live_blocks() == 1);
    *module_counter() = 1;
    strcpy(module_name(), "main");
    *module_local() = 99;

    pthread_barrier_init(&written, NULL, THREADS);
    pthread_t threads[THREADS];
    for (long i = 0; i < THREADS; ++i) CHECK(pthread_create(&threads[i], NULL, worker, (void *)i) == 0);
    for (int i = 0; i < THREADS; ++i) CHECK(pthread_join(threads[i], NULL) == 0);
    for (int i = 0; i < THREADS; ++i)
        for (int j = i + 1; j < THREADS; ++j) CHECK(addresses[i] != addresses[j]);

    CHECK(*module_counter() == 1);
    CHECK(strcmp(module_name(), "main") == 0);
    CHECK(*module_local() == 99);
    CHECK(orbis_prx_tls_live_blocks() == 1);

    for (long i = 0; i < CHURN; ++i) {
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, short_lived, (void *)i) == 0);
        CHECK(pthread_join(thread, NULL) == 0);
    }
    CHECK(orbis_prx_tls_live_blocks() == 1);
    CHECK(*module_counter() == 1);

    for (long i = 0; i < CHURN; ++i) CHECK(module_spawn_and_join(i) == 0);
    CHECK(orbis_prx_tls_live_blocks() == 1);
    CHECK(*module_counter() == 1);

    module_thread_exit();
    CHECK(orbis_prx_tls_live_blocks() == 0);
    module_thread_exit();
    check_template();
    CHECK(orbis_prx_tls_live_blocks() == 1);
    puts("PRX TLS host behavior passed");
    return 0;
}
