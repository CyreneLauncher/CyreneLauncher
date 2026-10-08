/* Host module for orbis_prx_tls.c: thread-locals in .tdata.*, .tbss.* and local-dynamic form. */
#define _GNU_SOURCE
#include <link.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>

__thread int tls_counter = 41;
__thread char tls_name[64] = "template";
__thread long tls_zero[32];
static __thread int tls_local = 5;

static int find_tls(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    const void **result = data;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) *header = &info->dlpi_phdr[i];
        if (header->p_type != PT_LOAD) continue;
        uintptr_t start = info->dlpi_addr + header->p_vaddr;
        if ((uintptr_t)(void *)find_tls < start || (uintptr_t)(void *)find_tls >= start + header->p_memsz)
            continue;
        for (int j = 0; j < info->dlpi_phnum; ++j)
            if (info->dlpi_phdr[j].p_type == PT_TLS) {
                result[0] = (const void *)(info->dlpi_addr + info->dlpi_phdr[j].p_vaddr);
                result[1] = (const void *)(uintptr_t)info->dlpi_phdr[j].p_filesz;
                result[2] = (const void *)(uintptr_t)info->dlpi_phdr[j].p_memsz;
            }
        return 1;
    }
    return 0;
}

__attribute__((visibility("hidden")))
void orbis_prx_tls_template(const unsigned char **image, size_t *file_size, size_t *memory_size) {
    const void *found[3] = {0};
    dl_iterate_phdr(find_tls, found);
    *image = found[0];
    *file_size = (size_t)(uintptr_t)found[1];
    *memory_size = (size_t)(uintptr_t)found[2];
}

int *module_counter(void) { return &tls_counter; }
char *module_name(void) { return tls_name; }
long *module_zero(void) { return tls_zero; }
int *module_local(void) { return &tls_local; }

__attribute__((visibility("hidden"))) void orbis_prx_tls_thread_exit(void);
void module_thread_exit(void) { orbis_prx_tls_thread_exit(); }

/* A thread this module starts (pthread_create is wrapped) releases its block on return. */
static void *module_worker(void *argument) {
    tls_counter = (int)(long)argument;
    return NULL;
}
int module_spawn_and_join(long value) {
    pthread_t thread;
    if (pthread_create(&thread, NULL, module_worker, (void *)value) != 0) return -1;
    return pthread_join(thread, NULL);
}
