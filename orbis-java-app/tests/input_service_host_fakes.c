/* Host fakes for orbis_input_service.cpp: scripted sceKeyboardRead batches on slot 0 (handle 7) and
 * slot 1 (handle 8), a ReadState snapshot for slot 1, and a mouse on index 0 (handle 9) that reports
 * queued 1-pixel moves to the right. */
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define RECORD 96
#define MAX_BATCHES 64
struct queue {
    unsigned char batches[MAX_BATCHES][16 * RECORD];
    int records[MAX_BATCHES];
    int head, tail;
};
static struct queue queues[2];
static unsigned char slot1_state[RECORD];
static int slot_reads[2];
static pthread_mutex_t fake_lock = PTHREAD_MUTEX_INITIALIZER;

static void write_record(unsigned char *record, uint64_t stamp, const uint16_t *keys) {
    int32_t one = 1, count = 0;
    memset(record, 0, RECORD);
    memcpy(record, &stamp, 8);
    memcpy(record + 16, &one, 4);
    for (int k = 0; k < 4 && keys[k] != 0; ++k) {
        memcpy(record + 32 + 2 * k, &keys[k], 2);
        ++count;
    }
    memcpy(record + 20, &count, 4);
}

/* Queues one buffered read's worth of records on a slot: each record is a stamp and the keys down. */
void fake_push_slot_batch(int slot, int records, const uint64_t *stamps, const uint16_t keys[][4]) {
    pthread_mutex_lock(&fake_lock);
    struct queue *q = &queues[slot];
    unsigned char *batch = q->batches[q->tail % MAX_BATCHES];
    for (int r = 0; r < records; ++r) write_record(batch + r * RECORD, stamps[r], keys[r]);
    q->records[q->tail % MAX_BATCHES] = records;
    ++q->tail;
    pthread_mutex_unlock(&fake_lock);
}

void fake_push_batch(int records, const uint64_t *stamps, const uint16_t keys[][4]) {
    fake_push_slot_batch(0, records, stamps, keys);
}

/* What ReadState reports on slot 1. */
void fake_set_slot1_state(uint64_t stamp, const uint16_t *keys) {
    pthread_mutex_lock(&fake_lock);
    write_record(slot1_state, stamp, keys);
    pthread_mutex_unlock(&fake_lock);
}

int fake_slot_reads(int slot) {
    pthread_mutex_lock(&fake_lock);
    int n = slot_reads[slot];
    pthread_mutex_unlock(&fake_lock);
    return n;
}

int sceKeyboardRead(int32_t handle, void *data, int32_t count) {
    pthread_mutex_lock(&fake_lock);
    int slot = handle == 8 ? 1 : 0;
    ++slot_reads[slot];
    struct queue *q = &queues[slot];
    int records = 0;
    if (q->head != q->tail) {
        records = q->records[q->head % MAX_BATCHES];
        if (records > count) records = count;
        memcpy(data, q->batches[q->head % MAX_BATCHES], (size_t)records * RECORD);
        ++q->head;
    }
    pthread_mutex_unlock(&fake_lock);
    return records;
}

int32_t sceKeyboardInit(void) { return 0; }
int32_t sceKeyboardOpen(int32_t user, int32_t type, int32_t index, void *param) {
    (void)user; (void)type; (void)param;
    return index == 0 ? 7 : index == 1 ? 8 : -1;
}
int32_t sceKeyboardClose(int32_t handle) { (void)handle; return 0; }
int32_t sceKeyboardReadState(int32_t handle, void *data) {
    pthread_mutex_lock(&fake_lock);
    if (handle == 8) memcpy(data, slot1_state, RECORD);
    else memset(data, 0, RECORD);
    pthread_mutex_unlock(&fake_lock);
    return 0;
}

typedef struct { int32_t ok; int32_t ok2; int32_t keycode; char unk[8]; } Key2Char;
/* HID usages 4-29 are a-z. */
int32_t sceKeyboardGetKey2Char(int32_t handle, _Bool unknown, int32_t locks, int32_t mods, int32_t key, Key2Char *out) {
    (void)handle; (void)unknown; (void)locks; (void)mods;
    memset(out, 0, sizeof(*out));
    if (key >= 4 && key <= 29) { out->ok = 1; out->keycode = 'a' + key - 4; }
    return 0;
}

struct mouse_sample {
    uint64_t timestamp;
    uint8_t connected;
    uint8_t padding[3];
    uint32_t buttons;
    int32_t x, y, wheel, tilt;
    uint8_t reserve[8];
};
static int pending_moves;

void fake_mouse_move(int samples) {
    pthread_mutex_lock(&fake_lock);
    pending_moves += samples;
    pthread_mutex_unlock(&fake_lock);
}

int32_t sceMouseInit(void) { return 0; }
int32_t sceMouseOpen(int32_t user, int32_t type, int32_t index, void *param) {
    (void)user; (void)type; (void)param;
    return index == 0 ? 9 : -1;
}
int32_t sceMouseRead(int32_t handle, void *data, int32_t count) {
    if (handle != 9) return -1;
    struct mouse_sample *out = data;
    pthread_mutex_lock(&fake_lock);
    int n = pending_moves < count ? pending_moves : count;
    for (int i = 0; i < n; ++i) {
        memset(&out[i], 0, sizeof(out[i]));
        out[i].connected = 1;
        out[i].x = 1;
    }
    pending_moves -= n;
    pthread_mutex_unlock(&fake_lock);
    return n;
}
int32_t sceMouseClose(int32_t handle) { (void)handle; return 0; }
int32_t sceSysmoduleLoadModule(uint32_t id) { (void)id; return 0; }
int32_t sceUserServiceInitialize(void *params) { (void)params; return 0; }
int32_t sceUserServiceGetInitialUser(int32_t *user) { *user = 1; return 0; }
int32_t orbis_display_set_cursor(int32_t x, int32_t y, int32_t visible) { (void)x; (void)y; (void)visible; return 0; }
int sceKernelDebugOutText(int channel, const char *text) { (void)channel; (void)text; return 0; }
int sceKernelUsleep(unsigned int us) { return usleep(us); }
void *scePthreadSelf(void) { return (void *)0; }
int scePthreadSetprio(void *thread, int priority) { (void)thread; (void)priority; return 0; }
