/* Host fakes for orbis_input_service.cpp: scripted sceKeyboardRead batches, no mouse. */
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define RECORD 96
#define MAX_BATCHES 64
static unsigned char batches[MAX_BATCHES][16 * RECORD];
static int batch_records[MAX_BATCHES];
static int batch_head, batch_tail;
static pthread_mutex_t fake_lock = PTHREAD_MUTEX_INITIALIZER;

/* Queues one read's worth of records: each record is stamp, then the keys down (0-terminated). */
void fake_push_batch(int records, const uint64_t *stamps, const uint16_t keys[][4]) {
    pthread_mutex_lock(&fake_lock);
    unsigned char *batch = batches[batch_tail % MAX_BATCHES];
    memset(batch, 0, sizeof(batches[0]));
    for (int r = 0; r < records; ++r) {
        unsigned char *record = batch + r * RECORD;
        int32_t one = 1, count = 0;
        memcpy(record, &stamps[r], 8);
        memcpy(record + 16, &one, 4);
        for (int k = 0; k < 4 && keys[r][k] != 0; ++k) {
            memcpy(record + 32 + 2 * k, &keys[r][k], 2);
            ++count;
        }
        memcpy(record + 20, &count, 4);
    }
    batch_records[batch_tail % MAX_BATCHES] = records;
    ++batch_tail;
    pthread_mutex_unlock(&fake_lock);
}

int sceKeyboardRead(int32_t handle, void *data, int32_t count) {
    (void)handle;
    pthread_mutex_lock(&fake_lock);
    int records = 0;
    if (batch_head != batch_tail) {
        records = batch_records[batch_head % MAX_BATCHES];
        if (records > count) records = count;
        memcpy(data, batches[batch_head % MAX_BATCHES], (size_t)records * RECORD);
        ++batch_head;
    }
    pthread_mutex_unlock(&fake_lock);
    return records;
}

int32_t sceKeyboardInit(void) { return 0; }
int32_t sceKeyboardOpen(int32_t user, int32_t type, int32_t index, void *param) {
    (void)user; (void)type; (void)param;
    return index == 0 ? 7 : -1;
}
int32_t sceKeyboardClose(int32_t handle) { (void)handle; return 0; }
int32_t sceKeyboardReadState(int32_t handle, void *data) { (void)handle; memset(data, 0, RECORD); return 0; }

typedef struct { int32_t ok; int32_t ok2; int32_t keycode; char unk[8]; } Key2Char;
/* HID usages 4-29 are a-z. */
int32_t sceKeyboardGetKey2Char(int32_t handle, _Bool unknown, int32_t locks, int32_t mods, int32_t key, Key2Char *out) {
    (void)handle; (void)unknown; (void)locks; (void)mods;
    memset(out, 0, sizeof(*out));
    if (key >= 4 && key <= 29) { out->ok = 1; out->keycode = 'a' + key - 4; }
    return 0;
}

int32_t sceMouseInit(void) { return 0; }
int32_t sceMouseOpen(int32_t user, int32_t type, int32_t index, void *param) {
    (void)user; (void)type; (void)index; (void)param;
    return -1;
}
int32_t sceMouseRead(int32_t handle, void *data, int32_t count) { (void)handle; (void)data; (void)count; return -1; }
int32_t sceMouseClose(int32_t handle) { (void)handle; return 0; }
int32_t sceSysmoduleLoadModule(uint32_t id) { (void)id; return 0; }
int32_t sceUserServiceInitialize(void *params) { (void)params; return 0; }
int32_t sceUserServiceGetInitialUser(int32_t *user) { *user = 1; return 0; }
int32_t orbis_display_set_cursor(int32_t x, int32_t y, int32_t visible) { (void)x; (void)y; (void)visible; return 0; }
int sceKernelDebugOutText(int channel, const char *text) { (void)channel; (void)text; return 0; }
int sceKernelUsleep(unsigned int us) { return usleep(us); }
void *scePthreadSelf(void) { return (void *)0; }
int scePthreadSetprio(void *thread, int priority) { (void)thread; (void)priority; return 0; }
