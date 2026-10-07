#define _POSIX_C_SOURCE 200809L
#include "orbis_input_service.h"
#include "orbis_mouse_abi.h"
#include "orbis_display_service.h"
#include "orbis_runtime.h"

#include <orbis/Keyboard.h>
#include <orbis/Sysmodule.h>
#include <orbis/UserService.h>
#include <orbis/libkernel.h>

#include <pthread.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#include <time.h>
#include <string.h>

/* The firmware's buffered read: the SDK header declares it as void(void), so it is bound here
 * by symbol. Disassembled from the console's libSceKeyboard: count must be 1-16, the result is
 * the number of 96-byte state records written (the ReadState layout, with a 64-bit timestamp),
 * one per change since the last read, oldest first. */
extern "C" int32_t orbis_keyboard_read(int32_t handle, void *data, int32_t count)
        __asm__("sceKeyboardRead");

namespace {
pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
pthread_t collector;
bool started;
bool stopping;
bool lifecycle_busy;
bool desktop_enabled;
bool focus_toggle_enabled = true;
bool diagnostics_enabled;
uint32_t keyboard_samples_logged;
uint32_t keyboard_transitions_logged;
bool swallowed_keys[256];
int keyboard_handle = -1;
/* Slots 1-3: combo receivers and some keyboards report keys on a slot other than 0. */
constexpr int kExtraKeyboardSlots = 3;
int extra_keyboard_handles[kExtraKeyboardSlots] = {-1, -1, -1};
int mouse_handle = -1;
/* Mouse indexes 1-3, for the same reason as the keyboard slots: a receiver may report there. */
constexpr int kExtraMouseSlots = 3;
int extra_mouse_handles[kExtraMouseSlots] = {-1, -1, -1};
/* Latest buttons per slot (0 = mouse_handle); events follow their union, so a dead slot that
 * keeps reporting "no buttons" cannot release a button held on another slot. */
uint32_t slot_buttons[1 + kExtraMouseSlots];
bool slot_motion_logged[1 + kExtraMouseSlots];
bool keyboard_initialized;
bool mouse_initialized;
orbis_input_focus focus = ORBIS_INPUT_FOCUS_CONSOLE;
orbis_input_state state{};
orbis_input_event queue[ORBIS_INPUT_QUEUE_CAPACITY];
uint32_t queue_size;
uint64_t generation;
uint16_t prior_keys[32];
uint32_t prior_count;
uint32_t prior_buttons;
uint64_t held_since[256];
uint64_t repeat_due[256];
/* Repeat diagnostics: the device's own timestamp at press, and whether the hold repeated. */
uint32_t down_stamp[256];
bool repeated[256];
/* The longest gap between keyboard reads while each key was held. */
uint64_t longest_gap[256];
uint64_t last_read_tick;
uint32_t hold_reports;
int32_t user_id = ORBIS_USER_SERVICE_USER_ID_INVALID;
int32_t mouse_x;
int32_t mouse_y;
uint64_t mouse_retry_after;
int32_t last_mouse_error;
uint32_t mouse_error_diagnostics;
uint64_t keyboard_retry_after;
int32_t last_keyboard_error;
uint32_t keyboard_error_diagnostics;
bool user_service_initialized;

int32_t open_keyboard() {
    int32_t result = sceSysmoduleLoadModule(ORBIS_SYSMODULE_KEYBOARD);
    if (result < 0) return result;
    if (!keyboard_initialized) {
        result = sceKeyboardInit();
        if (result < 0) return result;
        keyboard_initialized = true;
    }
    keyboard_handle = sceKeyboardOpen(user_id, 0, 0, nullptr);
    return keyboard_handle < 0 ? keyboard_handle : 0;
}

int32_t open_mouse() {
    int32_t result = sceSysmoduleLoadModule(ORBIS_SYSMODULE_MOUSE);
    if (result < 0) return result;
    if (!mouse_initialized) {
        result = sceMouseInit();
        if (result < 0) return result;
        mouse_initialized = true;
    }
    mouse_handle = sceMouseOpen(user_id, 0, 0, nullptr);
    return mouse_handle < 0 ? mouse_handle : 0;
}

uint64_t now_us() {
    struct timespec ts{};
    clock_gettime(ORBIS_CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000u +
           static_cast<uint64_t>(ts.tv_nsec / 1000);
}

/* Diagnostics (runner.conf input_diagnostics) go to the kernel log and, with a log path, to a file:
 * lines collect in memory, stamped in ms, and the collector appends them outside the input lock. */
pthread_mutex_t diag_lock = PTHREAD_MUTEX_INITIALIZER;
char diag_path[256];
char diag_buffer[128 * 1024];
size_t diag_used;
uint32_t diag_dropped;
uint64_t diag_flushed_at;

void diag_line(const char *line) {
    pthread_mutex_lock(&diag_lock);
    /* The kernel log is slow and was written under the input lock; with a file it is skipped. */
    if (!diag_path[0]) sceKernelDebugOutText(0, line);
    if (diag_path[0]) {
        char stamp[24];
        int head = snprintf(stamp, sizeof(stamp), "%llu ", static_cast<unsigned long long>(now_us() / 1000));
        size_t length = strlen(line);
        if (head > 0 && diag_used + static_cast<size_t>(head) + length < sizeof(diag_buffer)) {
            memcpy(diag_buffer + diag_used, stamp, static_cast<size_t>(head));
            memcpy(diag_buffer + diag_used + head, line, length);
            diag_used += static_cast<size_t>(head) + length;
        } else {
            ++diag_dropped;
        }
    }
    pthread_mutex_unlock(&diag_lock);
}

/* Appends the collected lines; called by the collector, never with the input lock held. */
void flush_diag(bool force) {
    static char pending[sizeof(diag_buffer) + 64];
    uint64_t now = now_us();
    if (!force && now - diag_flushed_at < 500000) return;
    diag_flushed_at = now;
    pthread_mutex_lock(&diag_lock);
    size_t length = diag_used;
    memcpy(pending, diag_buffer, length);
    diag_used = 0;
    if (diag_dropped != 0) {
        length += static_cast<size_t>(snprintf(pending + length, 64, "orbis-java-input: %u lines dropped\n", diag_dropped));
        diag_dropped = 0;
    }
    char path[sizeof(diag_path)];
    memcpy(path, diag_path, sizeof(path));
    pthread_mutex_unlock(&diag_lock);
    if (length == 0 || !path[0]) return;
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd < 0) return;
    for (size_t done = 0; done < length;) {
        ssize_t n = write(fd, pending + done, length - done);
        if (n <= 0) break;
        done += static_cast<size_t>(n);
    }
    close(fd);
}

/* Bounded kernel-log dumps; a file takes a whole typing test. */
uint32_t diag_cap(uint32_t uart) {
    return diag_path[0] ? 20000 : uart;
}

bool key_down(uint16_t key, const uint16_t *keys, uint32_t count) {
    for (uint32_t i = 0; i < count; ++i) if (keys[i] == key) return true;
    return false;
}

void snapshot_event(orbis_input_event *event, uint32_t type) {
    memset(event, 0, sizeof(*event));
    event->abi_version = ORBIS_INPUT_ABI_VERSION;
    event->struct_size = sizeof(*event);
    event->type = type;
    event->focus = focus;
    event->monotonic_us = now_us();
    event->reset_generation = generation;
    event->modifiers = state.modifiers;
    event->x = state.x;
    event->y = state.y;
    memcpy(event->pressed_keys, state.pressed_keys, sizeof(event->pressed_keys));
    event->pressed_buttons = state.pressed_buttons;
}

void reset_queue_locked() {
    ++generation;
    state.reset_generation = generation;
    queue_size = 0;
    orbis_input_event reset{};
    snapshot_event(&reset, ORBIS_INPUT_EVENT_RESET);
    queue[queue_size++] = reset;
    pthread_cond_broadcast(&changed);
}

void reset_overflow_locked() {
    bool owners[2] = {false, false};
    for (uint32_t i = 0; i < queue_size; ++i)
        if (queue[i].focus <= ORBIS_INPUT_FOCUS_DESKTOP) owners[queue[i].focus] = true;
    owners[focus] = true;
    ++generation;
    state.reset_generation = generation;
    queue_size = 0;
    for (uint32_t owner = 0; owner < 2; ++owner) {
        if (!owners[owner]) continue;
        orbis_input_event reset{};
        snapshot_event(&reset, ORBIS_INPUT_EVENT_RESET);
        reset.focus = owner;
        if (owner != static_cast<uint32_t>(focus)) {
            memset(reset.pressed_keys, 0, sizeof(reset.pressed_keys));
            reset.pressed_buttons = 0;
            reset.modifiers = 0;
        }
        queue[queue_size++] = reset;
    }
    pthread_cond_broadcast(&changed);
}

void reset_keyboard_locked() {
    memset(state.pressed_keys, 0, sizeof(state.pressed_keys));
    state.modifiers = 0;
    memset(prior_keys, 0, sizeof(prior_keys));
    prior_count = 0;
    memset(held_since, 0, sizeof(held_since));
    memset(repeat_due, 0, sizeof(repeat_due));
    memset(swallowed_keys, 0, sizeof(swallowed_keys));
    reset_overflow_locked();
}

void suppress_held_locked(const uint16_t *keys, uint32_t count) {
    memset(held_since, 0, sizeof(held_since));
    memset(repeat_due, 0, sizeof(repeat_due));
    for (uint32_t i = 0; i < count; ++i)
        if (keys[i] < 256) swallowed_keys[keys[i]] = true;
}

void transfer_focus_locked(orbis_input_focus next) {
    orbis_input_focus previous = focus;
    suppress_held_locked(prior_keys, prior_count);
    focus = next;
    state.focus = next;
    memset(state.pressed_keys, 0, sizeof(state.pressed_keys));
    state.pressed_buttons = 0;
    state.modifiers = 0;
    prior_count = 0;
    prior_buttons = 0;
    reset_queue_locked();
    if (previous != next && queue_size < ORBIS_INPUT_QUEUE_CAPACITY) {
        orbis_input_event release = queue[0];
        release.focus = previous;
        queue[queue_size++] = release;
        pthread_cond_broadcast(&changed);
    }
}

bool keyboard_event(uint32_t type) {
    return type == ORBIS_INPUT_EVENT_KEY_DOWN || type == ORBIS_INPUT_EVENT_KEY_UP ||
           type == ORBIS_INPUT_EVENT_TEXT || type == ORBIS_INPUT_EVENT_MODIFIERS;
}

void enqueue_locked(const orbis_input_event &event) {
    /* Motion replaces the newest queued motion of its owner when only keyboard events lie after it:
     * they do not depend on the pointer, the total movement is kept, and a key held while the mouse
     * moves (its repeats between the motions) no longer leaves one queued motion per sample. */
    if (event.type == ORBIS_INPUT_EVENT_MOTION) {
        for (uint32_t i = queue_size; i-- > 0;) {
            const orbis_input_event &queued = queue[i];
            if (queued.focus != event.focus) continue;
            if (queued.type == ORBIS_INPUT_EVENT_MOTION) {
                queue[i] = event;
                pthread_cond_broadcast(&changed);
                return;
            }
            if (!keyboard_event(queued.type)) break;
        }
    }
    if (queue_size == ORBIS_INPUT_QUEUE_CAPACITY) {
        reset_overflow_locked();
        return;
    }
    queue[queue_size++] = event;
    pthread_cond_broadcast(&changed);
}

/*
 * The SDK's OrbisKeyboardData is reverse-engineered and the console disagrees with it
 * (constant nkeys=1, keycode 0, timestamp 0). Reads go into an oversized canary-filled
 * buffer so a larger firmware structure cannot overrun the stack, and debug mode reports
 * how many bytes the firmware writes and dumps the buffer whenever its content changes.
 */
constexpr size_t kKeyboardReadBytes = 256;
constexpr uint8_t kKeyboardCanary = 0xa5;
uint8_t keyboard_last_dump[1 + kExtraKeyboardSlots][kKeyboardReadBytes];
/* Buffered reads: at most 16 records of 96 bytes per call (the firmware's limit). */
constexpr int32_t kKeyboardBatch = 16;
constexpr size_t kKeyboardRecordBytes = 96;
/* The keyboard is read through its primary slot every pass; the others are polled for keys. A slot
 * that reports a key becomes primary (a wired keyboard has reported on slot 1 with slot 0 silent),
 * so the slot in use gets the buffered, replayed reads. Whether the buffered call works is per slot. */
bool slot_buffered[1 + kExtraKeyboardSlots] = {true, true, true, true};
int primary_slot;
uint64_t last_keyboard_stamp;
OrbisKeyboardData last_slot0;   /* the primary slot's latest state */
/* The slots other than the primary can block the collector for hundreds of ms per read: poll them
 * every 250 ms, and every pass for 2 s after one reports a key or modifier (a key also makes that
 * slot the primary). Between polls their last state is reused. */
constexpr uint64_t kIdleExtraPollUs = 250000;
constexpr uint64_t kActiveExtraUs = 2000000;
OrbisKeyboardData cached_extras;
uint64_t extras_due;
uint64_t extras_active_until;
uint32_t buffered_reads_logged;
bool keyboard_size_logged[1 + kExtraKeyboardSlots];

void log_keyboard_read(int slot, const uint8_t *bytes, int32_t result) {
    if (!diagnostics_enabled) return;
    size_t written = kKeyboardReadBytes;
    while (written > 0 && bytes[written - 1] == kKeyboardCanary) --written;
    if (!keyboard_size_logged[slot]) {
        keyboard_size_logged[slot] = true;
        char line[160];
        snprintf(line, sizeof(line), "orbis-java-input: kbd slot %d read result=0x%08x wrote %u of %u bytes (sdk struct %u)\n",
                 slot, static_cast<unsigned>(result), static_cast<unsigned>(written),
                 static_cast<unsigned>(kKeyboardReadBytes), static_cast<unsigned>(sizeof(OrbisKeyboardData)));
        diag_line(line);
    }
    if (keyboard_samples_logged >= diag_cap(48) || memcmp(bytes, keyboard_last_dump[slot], kKeyboardReadBytes) == 0) return;
    memcpy(keyboard_last_dump[slot], bytes, kKeyboardReadBytes);
    ++keyboard_samples_logged;
    size_t shown = written < 160 ? written : 160;
    char line[420];
    int used = snprintf(line, sizeof(line), "orbis-java-input: kbd slot %d change %u wrote=%u bytes=",
                        slot, keyboard_samples_logged, static_cast<unsigned>(written));
    for (size_t i = 0; i < shown && used < static_cast<int>(sizeof(line)) - 3; ++i)
        used += snprintf(line + used, sizeof(line) - used, "%02x", bytes[i]);
    snprintf(line + used, sizeof(line) - used, "\n");
    diag_line(line);
}

/* With diagnostics, checks the buffered read's record count against what it wrote. */
void log_buffered_read(const uint8_t *batch, int32_t result) {
    if (result >= 0 && result <= kKeyboardBatch && (result == 0 || buffered_reads_logged >= diag_cap(8))) return;
    size_t written = kKeyboardBatch * kKeyboardRecordBytes + kKeyboardReadBytes;
    while (written > 0 && batch[written - 1] == kKeyboardCanary) --written;
    ++buffered_reads_logged;
    char line[160];
    snprintf(line, sizeof(line), "orbis-java-input: kbd buffered read result=0x%08x wrote %u bytes (%d records expect %u)%s\n",
             static_cast<unsigned>(result), static_cast<unsigned>(written), result,
             static_cast<unsigned>(result > 0 ? result * kKeyboardRecordBytes : 0),
             result < 0 || result > kKeyboardBatch ? ", falling back to ReadState" : "");
    diag_line(line);
}

void transition_locked(uint32_t type, uint32_t raw_key, uint32_t scalar) {
    if (diagnostics_enabled && keyboard_transitions_logged < diag_cap(64) && type <= ORBIS_INPUT_EVENT_TEXT) {
        ++keyboard_transitions_logged;
        char line[128];
        snprintf(line, sizeof(line), "orbis-java-input: kbd transition type=%u raw=%u scalar=%u focus=%u\n",
                 type, raw_key, scalar, static_cast<unsigned>(focus));
        diag_line(line);
    }
    orbis_input_event event{};
    snapshot_event(&event, type);
    event.raw_key = raw_key;
    event.unicode_scalar = scalar;
    enqueue_locked(event);
}

/* Opens keyboard slots 1-3 best effort; slot 0 keeps the existing retry path. */
void open_extra_keyboards() {
    for (int i = 0; i < kExtraKeyboardSlots; ++i) {
        if (extra_keyboard_handles[i] >= 0) continue;
        int32_t handle = sceKeyboardOpen(user_id, 0, i + 1, nullptr);
        if (diagnostics_enabled) {
            char line[96];
            snprintf(line, sizeof(line), "orbis-java-input: kbd slot %d open 0x%08x\n", i + 1,
                     static_cast<unsigned>(handle));
            diag_line(line);
        }
        if (handle >= 0) extra_keyboard_handles[i] = handle;
    }
}

void close_extra_keyboards() {
    for (int i = 0; i < kExtraKeyboardSlots; ++i) {
        if (extra_keyboard_handles[i] >= 0) sceKeyboardClose(extra_keyboard_handles[i]);
        extra_keyboard_handles[i] = -1;
    }
}

/* With diagnostics, compares how long the keyboard says a key was held with how long this
 * service believed it was: a stale "still down" state shows as a long service hold over a
 * short device hold. Reported when the hold repeated or the two disagree by >150 ms. */
void report_hold(uint16_t key, uint32_t release_stamp, uint64_t read_tick) {
    if (!diagnostics_enabled || hold_reports >= diag_cap(64) || held_since[key] == 0) return;
    uint64_t service_ms = (read_tick - held_since[key]) / 1000;
    uint64_t device_ms = (uint32_t)(release_stamp - down_stamp[key]) / 1000;
    uint64_t gap = service_ms > device_ms ? service_ms - device_ms : device_ms - service_ms;
    if (!repeated[key] && gap <= 150) return;
    ++hold_reports;
    char line[160];
    snprintf(line, sizeof(line), "orbis-java-input: kbd hold key=%u device=%llu ms service=%llu ms repeated=%d longest read gap=%llu ms\n",
             key, (unsigned long long)device_ms, (unsigned long long)service_ms, repeated[key] ? 1 : 0,
             (unsigned long long)(longest_gap[key] / 1000));
    diag_line(line);
}

/* Opens mouse indexes 1-3 best effort; index 0 keeps the existing retry path. */
void open_extra_mice() {
    for (int i = 0; i < kExtraMouseSlots; ++i) {
        if (extra_mouse_handles[i] >= 0) continue;
        int32_t handle = sceMouseOpen(user_id, 0, i + 1, nullptr);
        if (diagnostics_enabled) {
            char line[96];
            snprintf(line, sizeof(line), "orbis-java-input: mouse slot %d open 0x%08x\n", i + 1,
                     static_cast<unsigned>(handle));
            diag_line(line);
        }
        if (handle >= 0) extra_mouse_handles[i] = handle;
    }
}

void close_extra_mice() {
    for (int i = 0; i < kExtraMouseSlots; ++i) {
        if (extra_mouse_handles[i] >= 0) sceMouseClose(extra_mouse_handles[i]);
        extra_mouse_handles[i] = -1;
        slot_buttons[i + 1] = 0;
    }
}

/* Applies one slot's samples: motion and wheel accumulate, buttons follow the union of slots.
 * Called with the lock held; returns whether the cursor moved. */
bool apply_mouse_samples_locked(int slot, const orbis_mouse_data_abi *samples, int32_t count) {
    bool cursor_changed = false;
    for (int32_t i = 0; i < count; ++i) {
        const orbis_mouse_data_abi &sample = samples[i];
        slot_buttons[slot] = sample.connected ? sample.buttons : 0;
        if (sample.connected) {
            int64_t nx = static_cast<int64_t>(mouse_x) + sample.x_axis;
            int64_t ny = static_cast<int64_t>(mouse_y) + sample.y_axis;
            if (nx < 0) nx = 0; if (nx > 1279) nx = 1279;
            if (ny < 0) ny = 0; if (ny > 719) ny = 719;
            mouse_x = static_cast<int32_t>(nx);
            mouse_y = static_cast<int32_t>(ny);
            state.x = mouse_x;
            state.y = mouse_y;
            if ((sample.x_axis != 0 || sample.y_axis != 0) && diagnostics_enabled &&
                !slot_motion_logged[slot]) {
                slot_motion_logged[slot] = true;
                char line[96];
                snprintf(line, sizeof(line), "orbis-java-input: mouse slot %d first motion %d,%d\n",
                         slot, sample.x_axis, sample.y_axis);
                diag_line(line);
            }
        }
        uint32_t buttons = 0;
        for (int j = 0; j <= kExtraMouseSlots; ++j) buttons |= slot_buttons[j];
        uint32_t changed = prior_buttons ^ buttons;
        for (uint32_t bit = 0; bit < 32; ++bit) {
            uint32_t mask = UINT32_C(1) << bit;
            if ((changed & mask) == 0) continue;
            if (buttons & mask) state.pressed_buttons |= mask;
            else state.pressed_buttons &= ~mask;
            orbis_input_event event{};
            snapshot_event(&event, (buttons & mask)
                ? ORBIS_INPUT_EVENT_BUTTON_DOWN : ORBIS_INPUT_EVENT_BUTTON_UP);
            event.button = bit;
            enqueue_locked(event);
        }
        prior_buttons = buttons;
        if (!sample.connected) continue;
        if (sample.x_axis != 0 || sample.y_axis != 0) {
            cursor_changed = true;
            orbis_input_event event{};
            snapshot_event(&event, ORBIS_INPUT_EVENT_MOTION);
            enqueue_locked(event);
        }
        if (sample.wheel != 0) {
            orbis_input_event event{};
            snapshot_event(&event, ORBIS_INPUT_EVENT_WHEEL);
            event.wheel_delta = sample.wheel;
            enqueue_locked(event);
        }
    }
    return cursor_changed;
}

/* Reads mouse indexes 1-3; a failing index is closed and reopened with the next start. */
bool read_extra_mice() {
    bool cursor_changed = false;
    for (int i = 0; i < kExtraMouseSlots; ++i) {
        int32_t handle = extra_mouse_handles[i];
        if (handle < 0) continue;
        orbis_mouse_data_abi samples[32]{};
        int32_t count = sceMouseRead(handle, samples, 32);
        if (count < 0 || count > 32) {
            sceMouseClose(handle);
            extra_mouse_handles[i] = -1;
            pthread_mutex_lock(&lock);
            slot_buttons[i + 1] = 0;
            pthread_mutex_unlock(&lock);
            continue;
        }
        if (count == 0) continue;
        pthread_mutex_lock(&lock);
        cursor_changed |= apply_mouse_samples_locked(i + 1, samples, count);
        pthread_mutex_unlock(&lock);
    }
    return cursor_changed;
}

int32_t keyboard_slot_handle(int slot) {
    return slot == 0 ? keyboard_handle : extra_keyboard_handles[slot - 1];
}

/* Reads every slot but the primary once into one union of their keys and modifiers. Returns the
 * first slot that reports a key (its state in *reporter), or -1. */
int read_extra_keyboards(OrbisKeyboardData *extras, OrbisKeyboardData *reporter) {
    memset(extras, 0, sizeof(*extras));
    int reporting = -1;
    int32_t count = 0;
    for (int slot = 0; slot <= kExtraKeyboardSlots; ++slot) {
        if (slot == primary_slot) continue;
        int32_t handle = keyboard_slot_handle(slot);
        if (handle < 0) continue;
        alignas(16) uint8_t raw[kKeyboardReadBytes];
        memset(raw, kKeyboardCanary, sizeof(raw));
        int32_t result = sceKeyboardReadState(handle, reinterpret_cast<OrbisKeyboardData *>(raw));
        log_keyboard_read(slot, raw, result);
        if (result < 0) {
            /* Slot 0 keeps its retry path; a failing extra slot closes until the next start. */
            if (slot != 0) {
                sceKeyboardClose(handle);
                extra_keyboard_handles[slot - 1] = -1;
            }
            continue;
        }
        OrbisKeyboardData extra{};
        memcpy(&extra, raw, sizeof(extra));
        if (extra.nkeys < 0 || extra.nkeys > 32) continue;
        if (reporting < 0)
            for (int32_t i = 0; i < extra.nkeys; ++i)
                if (extra.keycodes[i] != 0) { reporting = slot; *reporter = extra; break; }
        extras->mods |= extra.mods;
        extras->locks |= extra.locks;
        for (int32_t i = 0; i < extra.nkeys; ++i) {
            uint16_t key = extra.keycodes[i];
            bool present = key == 0;
            for (int32_t j = 0; j < count && !present; ++j) present = extras->keycodes[j] == key;
            if (!present && count < 32) extras->keycodes[count++] = key;
        }
    }
    extras->nkeys = count;
    return reporting;
}

/* Makes slot the primary. Its buffered queue may still hold changes from before, which were already
 * seen through ReadState: they are drained unapplied, and the newest drained state (else the
 * ReadState one) becomes the primary's state. */
void promote_keyboard_slot(int slot, const OrbisKeyboardData &state_now) {
    if (diagnostics_enabled) {
        char line[128];
        snprintf(line, sizeof(line), "orbis-java-input: kbd slot %d reports keys; reading it every pass (was slot %d)\n",
                 slot, primary_slot);
        diag_line(line);
    }
    primary_slot = slot;
    last_keyboard_stamp = 0;
    OrbisKeyboardData latest = state_now;
    int32_t handle = keyboard_slot_handle(slot);
    if (slot_buffered[slot]) {
        alignas(16) uint8_t batch[kKeyboardBatch * kKeyboardRecordBytes + kKeyboardReadBytes];
        for (int round = 0; round < 8; ++round) {
            memset(batch, kKeyboardCanary, sizeof(batch));
            int32_t result = orbis_keyboard_read(handle, batch, kKeyboardBatch);
            if (result < 0 || result > kKeyboardBatch) {
                slot_buffered[slot] = false;
                log_buffered_read(batch, result);
                break;
            }
            for (int32_t r = 0; r < result; ++r) {
                uint64_t stamp;
                memcpy(&stamp, batch + r * kKeyboardRecordBytes, sizeof(stamp));
                if (stamp >= last_keyboard_stamp) {
                    last_keyboard_stamp = stamp;
                    memcpy(&latest, batch + r * kKeyboardRecordBytes, sizeof(latest));
                }
            }
            if (result < kKeyboardBatch) break;
        }
    }
    if (latest.nkeys >= 0 && latest.nkeys <= 32) last_slot0 = latest;
}

/* Unions the extra slots into sample; key code 0 means "no key" and is dropped. */
void merge_extra_keyboards(OrbisKeyboardData *sample, const OrbisKeyboardData &extras) {
    int32_t count = 0;
    for (int32_t i = 0; i < sample->nkeys && i < 32; ++i)
        if (sample->keycodes[i] != 0) sample->keycodes[count++] = sample->keycodes[i];
    sample->mods |= extras.mods;
    sample->locks |= extras.locks;
    for (int32_t i = 0; i < extras.nkeys; ++i) {
        uint16_t key = extras.keycodes[i];
        bool present = false;
        for (int32_t j = 0; j < count && !present; ++j) present = sample->keycodes[j] == key;
        if (!present && count < 32) sample->keycodes[count++] = key;
    }
    sample->nkeys = count;
}

/* Key translations (sceKeyboardGetKey2Char, an IPC) are fetched before the input lock is taken and
 * kept here, so the lock - which the application's per-frame read waits for - is never held across
 * the IPC. Only the collector uses the cache. */
struct translation {
    uint16_t key;
    uint8_t valid;
    uint8_t ok;
    uint32_t mods;
    uint32_t locks;
    uint32_t scalar;
};
constexpr size_t kTranslationSlots = 512;
translation translations[kTranslationSlots];

translation &translation_slot(uint16_t key, uint32_t mods, uint32_t locks) {
    size_t hash = (key * 31u + (mods & 0xffu) * 7u + (locks & 7u)) % kTranslationSlots;
    return translations[hash];
}

/* The printable character key makes with these modifiers and locks, from the cache or the IPC. */
bool translate_key(int32_t handle, uint32_t locks, uint32_t mods, uint16_t key, uint32_t *scalar) {
    translation &slot = translation_slot(key, mods, locks);
    if (!(slot.valid && slot.key == key && slot.mods == mods && slot.locks == locks)) {
        OrbisKeyboardKey2Char translated{};
        bool ok = sceKeyboardGetKey2Char(handle, false, static_cast<int32_t>(locks), static_cast<int32_t>(mods),
                                         key, &translated) == 0 && translated.ok &&
                  translated.keycode >= 32 && translated.keycode <= 126;
        slot = translation{key, 1, static_cast<uint8_t>(ok ? 1 : 0), mods, locks,
                           ok ? static_cast<uint32_t>(translated.keycode) : 0u};
    }
    *scalar = slot.scalar;
    return slot.ok != 0;
}

/* Fetches the translations a sample's keys will need, outside the input lock. */
void prefetch_translations(int32_t handle, const OrbisKeyboardData &sample) {
    uint32_t scalar;
    for (int32_t i = 0; i < sample.nkeys && i < 32; ++i)
        if (sample.keycodes[i] != 0 && sample.keycodes[i] < 256)
            translate_key(handle, sample.locks, sample.mods, sample.keycodes[i], &scalar);
}

/* Applies one keyboard state: presses and releases against prior_keys, at service time tick. */
bool apply_keyboard_sample_locked(const OrbisKeyboardData &sample, int32_t handle, uint64_t tick,
                                  uint32_t stamp) {
    bool refresh_cursor = false;
    uint16_t keys[32];
    uint32_t count = static_cast<uint32_t>(sample.nkeys);
    for (uint32_t i = 0; i < count; ++i) keys[i] = sample.keycodes[i];
    if (state.modifiers != sample.mods) {
        state.modifiers = sample.mods;
        transition_locked(ORBIS_INPUT_EVENT_MODIFIERS, 0, 0);
    }
    for (uint32_t i = 0; i < count; ++i) {
        uint16_t key = keys[i];
        if (key >= 256) continue;
        if (swallowed_keys[key]) continue;
        if (!key_down(key, prior_keys, prior_count)) {
            held_since[key] = tick;
            repeat_due[key] = tick + 500000;
            down_stamp[key] = stamp;
            repeated[key] = false;
            longest_gap[key] = 0;
            if (key == ORBIS_KEYBOARD_KEY_F12 && desktop_enabled && focus_toggle_enabled) {
                orbis_input_focus next = focus == ORBIS_INPUT_FOCUS_CONSOLE
                        ? ORBIS_INPUT_FOCUS_DESKTOP : ORBIS_INPUT_FOCUS_CONSOLE;
                swallowed_keys[key] = true;
                transfer_focus_locked(next);
                suppress_held_locked(keys, count);
                refresh_cursor = true;
                continue;
            }
            state.pressed_keys[key >> 3] |= static_cast<uint8_t>(1u << (key & 7));
            transition_locked(ORBIS_INPUT_EVENT_KEY_DOWN, key, 0);
            // The initial edit/commit is represented by KEY_DOWN only.
            if (key == ORBIS_KEYBOARD_KEY_BACKSPACE ||
                key == ORBIS_KEYBOARD_KEY_RETURN) continue;
            uint32_t scalar;
            if (translate_key(handle, sample.locks, sample.mods, key, &scalar))
                transition_locked(ORBIS_INPUT_EVENT_TEXT, key, scalar);
        }
    }
    for (uint32_t i = 0; i < prior_count; ++i) {
        uint16_t key = prior_keys[i];
        if (key >= 256) continue;
        if (swallowed_keys[key]) {
            if (!key_down(key, keys, count)) {
                swallowed_keys[key] = false;
                held_since[key] = repeat_due[key] = 0;
            }
            continue;
        }
        if (!key_down(key, keys, count)) {
            report_hold(key, stamp, tick);
            held_since[key] = repeat_due[key] = 0;
            state.pressed_keys[key >> 3] &= static_cast<uint8_t>(~(1u << (key & 7)));
            transition_locked(ORBIS_INPUT_EVENT_KEY_UP, key, 0);
        }
    }
    /* Clear stale suppression only after the release loop has consumed it. */
    for (uint32_t key = 0; key < 256; ++key)
        if (swallowed_keys[key] && !key_down(static_cast<uint16_t>(key), keys, count))
            swallowed_keys[key] = false;
    memcpy(prior_keys, keys, count * sizeof(keys[0]));
    prior_count = count;
    return refresh_cursor;
}

/* Emits at most one due repeat for the keys held as of tick. */
void repeat_held_keys_locked(int32_t handle, uint64_t tick, uint32_t locks, uint32_t mods) {
    for (uint32_t i = 0; i < prior_count; ++i) {
        uint16_t key = prior_keys[i];
        if (key >= 256 || held_since[key] == 0 || tick < repeat_due[key]) continue;
        if (key == ORBIS_KEYBOARD_KEY_RETURN) continue;
        // Raw Backspace is an edit key regardless of firmware translation.
        if (key == ORBIS_KEYBOARD_KEY_BACKSPACE) {
            transition_locked(ORBIS_INPUT_EVENT_TEXT, key, '\b');
            repeat_due[key] = tick + 33000;
            repeated[key] = true;
            return;
        }
        uint32_t scalar;
        if (translate_key(handle, locks, mods, key, &scalar)) {
            if (diagnostics_enabled && !repeated[key]) {
                char line[128];
                snprintf(line, sizeof(line), "orbis-java-input: kbd repeat starts key=%u held %llu ms\n", key,
                         static_cast<unsigned long long>((tick - held_since[key]) / 1000));
                diag_line(line);
            }
            transition_locked(ORBIS_INPUT_EVENT_TEXT, key, scalar);
            repeat_due[key] = tick + 33000;
            repeated[key] = true;
            return;
        }
    }
}

uint32_t slow_call_reports;

/* With diagnostics, names any device call that blocked the collector for over 40 ms. */
void note_slow(const char *what, uint64_t us) {
    if (!diagnostics_enabled || us <= 40000 || slow_call_reports >= diag_cap(48)) return;
    ++slow_call_reports;
    char line[128];
    snprintf(line, sizeof(line), "orbis-java-input: slow %s %llu ms\n", what,
             (unsigned long long)(us / 1000));
    diag_line(line);
}

/* Above the game's default 700 so a busy render or loader thread cannot hold the collector
 * between a read and its repeat decisions; it sleeps 8 ms per pass, so it never hogs a core. */
constexpr int kCollectorPriority = 0x180;

constexpr uint64_t kRepeatFreshUs = 20000;

void *collect(void *) {
    int32_t prio = scePthreadSetprio(scePthreadSelf(), kCollectorPriority);
    if (diagnostics_enabled) {
        char line[96];
        snprintf(line, sizeof(line), "orbis-java-input: collector priority %d: 0x%08x\n",
                 kCollectorPriority, static_cast<unsigned>(prio));
        diag_line(line);
    }
    while (true) {
        pthread_mutex_lock(&lock);
        bool quit = stopping;
        int32_t handle = keyboard_handle;
        int32_t mhandle = mouse_handle;
        pthread_mutex_unlock(&lock);
        if (quit) break;
        uint64_t retry_tick = now_us();
        if (handle < 0 && retry_tick >= keyboard_retry_after) {
            int32_t open_result = open_keyboard();
            keyboard_retry_after = now_us() + 1000000;
            if (open_result < 0 && open_result != last_keyboard_error &&
                keyboard_error_diagnostics < 8) {
                char message[128];
                snprintf(message, sizeof(message), "orbis-java-input: keyboard open error 0x%08x\n",
                         static_cast<unsigned int>(open_result));
                diag_line(message);
                last_keyboard_error = open_result;
                ++keyboard_error_diagnostics;
            }
            handle = keyboard_handle;
        }
        if (mhandle < 0 && retry_tick >= mouse_retry_after) {
            int32_t open_result = open_mouse();
            mouse_retry_after = now_us() + 1000000;
            if (open_result < 0 && open_result != last_mouse_error &&
                mouse_error_diagnostics < 8) {
                char message[128];
                snprintf(message, sizeof(message), "orbis-java-input: mouse open error 0x%08x\n",
                         static_cast<unsigned int>(open_result));
                diag_line(message);
                last_mouse_error = open_result;
                ++mouse_error_diagnostics;
            }
            mhandle = mouse_handle;
        }
        /* The primary slot is read through the buffered call, so every change since the last pass
         * is replayed in order: a tap shorter than a stalled pass is neither lost nor stretched.
         * ReadState snapshots remain the fallback where the buffered call is refused. */
        alignas(16) uint8_t batch[kKeyboardBatch * kKeyboardRecordBytes + kKeyboardReadBytes];
        int32_t result = 0;
        int32_t records = 0;
        uint64_t read_tick = 0;
        uint64_t call_us = 0;
        if (primary_slot != 0 && keyboard_slot_handle(primary_slot) < 0) {
            primary_slot = 0;
            last_keyboard_stamp = 0;
        }
        const int slot = primary_slot;
        const int32_t khandle = slot == 0 ? handle : keyboard_slot_handle(slot);
        bool &buffered_reads = slot_buffered[slot];
        if (handle >= 0) {
            uint64_t before = now_us();
            if (buffered_reads) {
                memset(batch, kKeyboardCanary, sizeof(batch));
                result = orbis_keyboard_read(khandle, batch, kKeyboardBatch);
                if (result >= 0 && result <= kKeyboardBatch) {
                    records = result;
                    log_buffered_read(batch, result);
                } else {
                    log_buffered_read(batch, result);
                    buffered_reads = false;
                }
            }
            if (!buffered_reads) {
                memset(batch, kKeyboardCanary, sizeof(batch));
                result = sceKeyboardReadState(khandle, reinterpret_cast<OrbisKeyboardData *>(batch));
                records = result >= 0 ? 1 : 0;
            }
            read_tick = now_us();
            call_us = read_tick - before;
            note_slow(buffered_reads ? "sceKeyboardRead" : "sceKeyboardReadState", call_us);
        }
        if (result < 0 && slot != 0) {
            /* The primary extra slot failed (unplugged): close it and fall back to slot 0. */
            sceKeyboardClose(khandle);
            extra_keyboard_handles[slot - 1] = -1;
            primary_slot = 0;
            last_keyboard_stamp = 0;
            memset(&last_slot0, 0, sizeof(last_slot0));
            pthread_mutex_lock(&lock);
            reset_keyboard_locked();
            pthread_mutex_unlock(&lock);
            if (diagnostics_enabled) {
                char message[128];
                snprintf(message, sizeof(message), "orbis-java-input: kbd slot %d read error 0x%08x; back to slot 0\n",
                         slot, static_cast<unsigned int>(result));
                diag_line(message);
            }
            result = 0;
            records = 0;
        } else if (result < 0) {
            keyboard_retry_after = now_us() + 1000000;
            sceKeyboardClose(handle);
            keyboard_handle = -1;
            last_keyboard_stamp = 0;
            pthread_mutex_lock(&lock);
            reset_keyboard_locked();
            pthread_mutex_unlock(&lock);
            if (result != last_keyboard_error && keyboard_error_diagnostics < 8) {
                char message[128];
                snprintf(message, sizeof(message),
                         "orbis-java-input: keyboard read error 0x%08x\n",
                         static_cast<unsigned int>(result));
                diag_line(message);
                last_keyboard_error = result;
                ++keyboard_error_diagnostics;
            }
        }
        bool refresh_cursor = false;
        if (handle >= 0 && result >= 0) {
            uint64_t extras_before = now_us();
            int promote = -1;
            OrbisKeyboardData promote_state{};
            if (extras_before >= extras_due) {
                promote = read_extra_keyboards(&cached_extras, &promote_state);
                note_slow("extra keyboard slots", now_us() - extras_before);
                if (cached_extras.nkeys > 0 || cached_extras.mods != 0)
                    extras_active_until = extras_before + kActiveExtraUs;
                extras_due = extras_before < extras_active_until ? 0 : extras_before + kIdleExtraPollUs;
            }
            const OrbisKeyboardData extras = cached_extras;
            if (diagnostics_enabled && records > 0) {
                char line[160];
                int used = snprintf(line, sizeof(line), "orbis-java-input: kbd read %d records call=%llu us stamps",
                                    records, static_cast<unsigned long long>(call_us));
                for (int32_t r = 0; r < records && used < static_cast<int>(sizeof(line)) - 24; ++r) {
                    uint64_t stamp;
                    memcpy(&stamp, batch + r * kKeyboardRecordBytes, sizeof(stamp));
                    used += snprintf(line + used, sizeof(line) - used, " %llu", static_cast<unsigned long long>(stamp));
                }
                snprintf(line + used, sizeof(line) - used, "\n");
                diag_line(line);
            }
            uint64_t newest = 0;
            for (int32_t r = 0; r < records; ++r) {
                uint64_t stamp;
                memcpy(&stamp, batch + r * kKeyboardRecordBytes, sizeof(stamp));
                if (stamp > newest) newest = stamp;
            }
            /* Translations for every state this pass can apply, and for repeats of held keys. */
            {
                OrbisKeyboardData candidate = last_slot0;
                merge_extra_keyboards(&candidate, extras);
                prefetch_translations(khandle, candidate);
                for (int32_t r = 0; r < records; ++r) {
                    memcpy(&candidate, batch + r * kKeyboardRecordBytes, sizeof(candidate));
                    if (candidate.nkeys < 0 || candidate.nkeys > 32) continue;
                    merge_extra_keyboards(&candidate, extras);
                    prefetch_translations(khandle, candidate);
                }
            }
            pthread_mutex_lock(&lock);
            uint64_t gap = last_read_tick != 0 ? read_tick - last_read_tick : 0;
            last_read_tick = read_tick;
            for (uint32_t i = 0; i < prior_count; ++i)
                if (prior_keys[i] < 256 && gap > longest_gap[prior_keys[i]]) longest_gap[prior_keys[i]] = gap;
            OrbisKeyboardData sample{};
            bool have_sample = false;
            for (int32_t r = 0; r < records; ++r) {
                const uint8_t *record = batch + r * kKeyboardRecordBytes;
                uint64_t stamp;
                memcpy(&stamp, record, sizeof(stamp));
                if (buffered_reads) {
                    /* Only changes newer than the last one applied; a device clock that went
                     * backwards (reconnect) restarts the sequence. */
                    if (stamp == 0) {
                        /* Keys without a timestamp: this firmware's records cannot be ordered,
                         * so fall back to snapshots rather than drop them. */
                        OrbisKeyboardData unstamped{};
                        memcpy(&unstamped, record, sizeof(unstamped));
                        bool keys_present = false;
                        for (int32_t k = 0; k < unstamped.nkeys && k < 32; ++k)
                            keys_present |= unstamped.keycodes[k] != 0;
                        if (!keys_present) continue;
                        buffered_reads = false;
                        if (diagnostics_enabled)
                            diag_line("orbis-java-input: kbd buffered record without timestamp, falling back to ReadState\n");
                    }
                    /* Each record is a whole snapshot, so one carrying the newest applied time again
                     * is harmless; skipping it dropped every change that shared a timestamp - a lost
                     * press, or a lost release that auto-repeat then turned into a burst. */
                    if (buffered_reads && stamp < last_keyboard_stamp && last_keyboard_stamp - stamp < 10000000u) {
                        if (diagnostics_enabled) {
                            char line[128];
                            snprintf(line, sizeof(line), "orbis-java-input: kbd skip older record stamp=%llu last=%llu\n",
                                     static_cast<unsigned long long>(stamp), static_cast<unsigned long long>(last_keyboard_stamp));
                            diag_line(line);
                        }
                        continue;
                    }
                    if (buffered_reads) last_keyboard_stamp = stamp;
                }
                log_keyboard_read(0, record, 0);
                memcpy(&sample, record, sizeof(sample));
                if (sample.nkeys < 0 || sample.nkeys > 32) {
                    reset_keyboard_locked();
                    continue;
                }
                last_slot0 = sample;
                merge_extra_keyboards(&sample, extras);
                /* Place each change at its device time relative to the newest one. */
                uint64_t behind = newest > stamp ? newest - stamp : 0;
                if (behind > 1000000u) behind = 1000000u;
                refresh_cursor |= apply_keyboard_sample_locked(sample, khandle, read_tick - behind,
                                                               static_cast<uint32_t>(stamp));
                have_sample = true;
            }
            if (!have_sample) {
                /* No change on the primary slot: the other slots alone may still have moved. */
                sample = last_slot0;
                merge_extra_keyboards(&sample, extras);
                refresh_cursor |= apply_keyboard_sample_locked(sample, khandle, read_tick,
                        static_cast<uint32_t>(last_keyboard_stamp));
            }
            /* A pass that blocked or came late cannot vouch that a key is still down: hold
             * repeats back until a prompt read confirms it. */
            if (call_us > kRepeatFreshUs || gap > 4 * kRepeatFreshUs) {
                for (uint32_t i = 0; i < prior_count; ++i) {
                    uint16_t key = prior_keys[i];
                    if (key < 256 && held_since[key] != 0 && repeat_due[key] < read_tick + 100000)
                        repeat_due[key] = read_tick + 100000;
                }
            } else if (now_us() - read_tick <= kRepeatFreshUs) {
                repeat_held_keys_locked(khandle, read_tick, sample.locks, state.modifiers);
            }
            pthread_mutex_unlock(&lock);
            /* After this pass's keys: the union of slots is unchanged by the handover, so it causes
             * no transitions; from the next pass the new primary is read on its own. */
            if (promote >= 0) {
                promote_keyboard_slot(promote, promote_state);
                extras_due = 0;
            }
        }

        if (refresh_cursor) {
            pthread_mutex_lock(&lock);
            int32_t cx = mouse_x, cy = mouse_y;
            bool visible = desktop_enabled && focus == ORBIS_INPUT_FOCUS_DESKTOP;
            pthread_mutex_unlock(&lock);
            orbis_display_set_cursor(cx, cy, visible);
        }

        pthread_mutex_lock(&lock);
        handle = mouse_handle;
        pthread_mutex_unlock(&lock);
        if (handle >= 0 && now_us() >= mouse_retry_after) {
            orbis_mouse_data_abi samples[32]{};
            int32_t sample_count = sceMouseRead(handle, samples, 32);
            if (sample_count > 32) sample_count = -1;
            if (sample_count < 0) {
                mouse_retry_after = now_us() + 1000000;
                sceMouseClose(handle);
                mouse_handle = -1;
                pthread_mutex_lock(&lock);
                prior_buttons = 0;
                state.pressed_buttons = 0;
                memset(slot_buttons, 0, sizeof(slot_buttons));
                reset_overflow_locked();
                pthread_mutex_unlock(&lock);
                if (sample_count != last_mouse_error && mouse_error_diagnostics < 8) {
                    char message[128];
                    snprintf(message, sizeof(message),
                             "orbis-java-input: mouse read error 0x%08x\n",
                             static_cast<unsigned int>(sample_count));
                    diag_line(message);
                    last_mouse_error = sample_count;
                    ++mouse_error_diagnostics;
                }
            }
            bool cursor_changed = false;
            if (sample_count > 0) {
                pthread_mutex_lock(&lock);
                cursor_changed = apply_mouse_samples_locked(0, samples, sample_count);
                pthread_mutex_unlock(&lock);
            }
            if (cursor_changed) {
                pthread_mutex_lock(&lock);
                int32_t cx = mouse_x, cy = mouse_y;
                bool visible = desktop_enabled && focus == ORBIS_INPUT_FOCUS_DESKTOP;
                pthread_mutex_unlock(&lock);
                orbis_display_set_cursor(cx, cy, visible);
            }
        }
        if (read_extra_mice()) {
            pthread_mutex_lock(&lock);
            int32_t cx = mouse_x, cy = mouse_y;
            bool visible = desktop_enabled && focus == ORBIS_INPUT_FOCUS_DESKTOP;
            pthread_mutex_unlock(&lock);
            orbis_display_set_cursor(cx, cy, visible);
        }
        if (diagnostics_enabled) flush_diag(false);
        sceKernelUsleep(8000);
    }
    flush_diag(true);
    return nullptr;
}

/* Start failures are once per run and otherwise invisible ("input service unavailable"). */
void report_start_failure(const char *stage, int32_t code) {
    char line[128];
    snprintf(line, sizeof(line), "orbis-java-input: start failed at %s: 0x%08x\n", stage,
             static_cast<unsigned int>(code));
    diag_line(line);
}

/* With diagnostics, each key event as the application takes it, and how long it waited. */
uint32_t deliveries_logged;
void log_delivery_locked(const orbis_input_event &event) {
    if (!diagnostics_enabled || deliveries_logged >= diag_cap(64) || event.type > ORBIS_INPUT_EVENT_TEXT ||
        event.type == ORBIS_INPUT_EVENT_MODIFIERS) return;
    ++deliveries_logged;
    char line[128];
    snprintf(line, sizeof(line), "orbis-java-input: deliver type=%u raw=%u scalar=%u waited=%llu ms queue=%u\n",
             event.type, event.raw_key, event.unicode_scalar,
             static_cast<unsigned long long>((now_us() - event.monotonic_us) / 1000), queue_size);
    diag_line(line);
}

int32_t initialize_devices() {
    int32_t result = 0;
    if (!user_service_initialized) {
        OrbisUserServiceInitializeParams params{};
        params.priority = ORBIS_KERNEL_PRIO_FIFO_LOWEST;
        result = sceUserServiceInitialize(&params);
        if (result < 0) { report_start_failure("sceUserServiceInitialize", result); return result; }
        user_service_initialized = true;
    }
    result = sceUserServiceGetInitialUser(&user_id);
    if (result < 0 || user_id < 0) {
        report_start_failure("sceUserServiceGetInitialUser", result < 0 ? result : user_id);
        return result < 0 ? result : user_id;
    }
    int32_t keyboard_result = open_keyboard();
    open_extra_keyboards();
    if (keyboard_result < 0) {
        char message[128];
        snprintf(message, sizeof(message),
                 "orbis-java-input: keyboard unavailable 0x%08x\n",
                 static_cast<unsigned int>(keyboard_result));
        diag_line(message);
    }
    int32_t mouse_result = open_mouse();
    if (mouse_initialized) open_extra_mice();
    if (mouse_result < 0) {
        char message[128];
        snprintf(message, sizeof(message),
                 "orbis-java-input: mouse unavailable 0x%08x\n",
                 static_cast<unsigned int>(mouse_result));
        diag_line(message);
    }
    uint64_t retry = now_us() + 1000000;
    if (keyboard_handle < 0) keyboard_retry_after = retry;
    if (mouse_handle < 0) mouse_retry_after = retry;
    return 0;
}
}

extern "C" int32_t orbis_input_start(void) {
    pthread_mutex_lock(&lock);
    while (lifecycle_busy) pthread_cond_wait(&changed, &lock);
    if (started) { pthread_mutex_unlock(&lock); return 0; }
    lifecycle_busy = true;
    stopping = false;
    pthread_mutex_unlock(&lock);
    int32_t result = initialize_devices();
    if (result < 0) {
        pthread_mutex_lock(&lock);
        lifecycle_busy = false;
        pthread_cond_broadcast(&changed);
        pthread_mutex_unlock(&lock);
        return result;
    }
    pthread_mutex_lock(&lock);
    memset(&state, 0, sizeof(state));
    state.abi_version = ORBIS_INPUT_ABI_VERSION;
    state.struct_size = sizeof(state);
    state.focus = focus;
    state.reset_generation = generation;
    started = true;
    pthread_mutex_unlock(&lock);
    int create_result = pthread_create(&collector, nullptr, collect, nullptr);
    if (create_result != 0) {
        report_start_failure("pthread_create (collector)", create_result);
        pthread_mutex_lock(&lock); started = false; pthread_mutex_unlock(&lock);
        if (keyboard_handle >= 0) sceKeyboardClose(keyboard_handle);
        keyboard_handle = -1;
        close_extra_keyboards();
        close_extra_mice();
        if (mouse_handle >= 0) sceMouseClose(mouse_handle);
        mouse_handle = -1;
        pthread_mutex_lock(&lock);
        lifecycle_busy = false;
        pthread_cond_broadcast(&changed);
        pthread_mutex_unlock(&lock);
        return -1;
    }
    pthread_mutex_lock(&lock);
    lifecycle_busy = false;
    pthread_cond_broadcast(&changed);
    pthread_mutex_unlock(&lock);
    return 0;
}

extern "C" void orbis_input_stop(void) {
    pthread_mutex_lock(&lock);
    while (lifecycle_busy) pthread_cond_wait(&changed, &lock);
    if (!started) { pthread_mutex_unlock(&lock); return; }
    lifecycle_busy = true;
    stopping = true;
    pthread_cond_broadcast(&changed);
    pthread_mutex_unlock(&lock);
    pthread_join(collector, nullptr);
    if (keyboard_handle >= 0) sceKeyboardClose(keyboard_handle);
    keyboard_handle = -1;
    close_extra_keyboards();
    close_extra_mice();
    if (mouse_handle >= 0) sceMouseClose(mouse_handle);
    mouse_handle = -1;
    pthread_mutex_lock(&lock);
    started = false;
    memset(state.pressed_keys, 0, sizeof(state.pressed_keys));
    state.pressed_buttons = 0;
    state.modifiers = 0;
    prior_count = prior_buttons = 0;
    memset(held_since, 0, sizeof(held_since));
    memset(repeat_due, 0, sizeof(repeat_due));
    memset(swallowed_keys, 0, sizeof(swallowed_keys));
    last_read_tick = 0;
    last_keyboard_stamp = 0;
    primary_slot = 0;
    for (bool &buffered : slot_buffered) buffered = true;
    memset(&last_slot0, 0, sizeof(last_slot0));
    memset(&cached_extras, 0, sizeof(cached_extras));
    extras_due = extras_active_until = 0;
    reset_queue_locked();
    pthread_cond_broadcast(&changed);
    lifecycle_busy = false;
    pthread_cond_broadcast(&changed);
    pthread_mutex_unlock(&lock);
}

extern "C" int32_t orbis_input_set_focus(orbis_input_focus next) {
    if (next != ORBIS_INPUT_FOCUS_CONSOLE && next != ORBIS_INPUT_FOCUS_DESKTOP) return -1;
    pthread_mutex_lock(&lock);
    bool changed = next != focus;
    if (next != focus) {
        transfer_focus_locked(next);
    }
    int32_t cx = mouse_x, cy = mouse_y;
    bool visible = desktop_enabled && focus == ORBIS_INPUT_FOCUS_DESKTOP;
    pthread_mutex_unlock(&lock);
    if (changed) orbis_display_set_cursor(cx, cy, visible);
    return 0;
}

extern "C" int32_t orbis_input_set_desktop_enabled(int32_t enabled) {
    pthread_mutex_lock(&lock);
    bool was_enabled = desktop_enabled;
    desktop_enabled = enabled != 0;
    bool focus_changed = false;
    if (!desktop_enabled && focus == ORBIS_INPUT_FOCUS_DESKTOP) {
        transfer_focus_locked(ORBIS_INPUT_FOCUS_CONSOLE);
        focus_changed = true;
    }
    int32_t cx = mouse_x, cy = mouse_y;
    bool visible = desktop_enabled && focus == ORBIS_INPUT_FOCUS_DESKTOP;
    pthread_mutex_unlock(&lock);
    if (was_enabled != (enabled != 0) || focus_changed)
        orbis_display_set_cursor(cx, cy, visible);
    return 0;
}

extern "C" int32_t orbis_input_set_focus_toggle_enabled(int32_t enabled) {
    pthread_mutex_lock(&lock);
    focus_toggle_enabled = enabled != 0;
    pthread_mutex_unlock(&lock);
    return 0;
}

extern "C" void orbis_input_set_diagnostics(int32_t enabled) {
    pthread_mutex_lock(&lock);
    diagnostics_enabled = enabled != 0;
    pthread_mutex_unlock(&lock);
}

extern "C" void orbis_input_set_diagnostics_log(const char *path) {
    pthread_mutex_lock(&diag_lock);
    snprintf(diag_path, sizeof(diag_path), "%s", path != nullptr ? path : "");
    pthread_mutex_unlock(&diag_lock);
}

extern "C" int32_t orbis_input_set_pointer(int32_t x, int32_t y) {
    if (x < 0) x = 0; if (x > 1279) x = 1279;
    if (y < 0) y = 0; if (y > 719) y = 719;
    pthread_mutex_lock(&lock);
    // A warp moves the reference point; it is not reported as motion.
    mouse_x = state.x = x;
    mouse_y = state.y = y;
    bool visible = desktop_enabled && focus == ORBIS_INPUT_FOCUS_DESKTOP;
    pthread_mutex_unlock(&lock);
    orbis_display_set_cursor(x, y, visible);
    return 0;
}

extern "C" int32_t orbis_input_read_event(orbis_input_focus requested,
        orbis_input_event *out, uint32_t timeout_ms) {
    if (out == nullptr || (requested != ORBIS_INPUT_FOCUS_CONSOLE && requested != ORBIS_INPUT_FOCUS_DESKTOP)) return -1;
    if (timeout_ms == 0) {
        /* The per-frame poll: an empty queue returns without a timed wait. The collector holds the
         * lock only briefly (no IPC under it), so waiting for it beats ending the frame's reads
         * early: a busy mouse kept the lock taken often enough that events piled up for seconds. */
        pthread_mutex_lock(&lock);
        for (uint32_t i = 0; i < queue_size; ++i) if (queue[i].focus == requested) {
            *out = queue[i];
            memmove(&queue[i], &queue[i + 1], (queue_size - i - 1) * sizeof(queue[0]));
            --queue_size;
            log_delivery_locked(*out);
            pthread_mutex_unlock(&lock);
            return 0;
        }
        int status = (!started || stopping) ? -2 : 1;
        pthread_mutex_unlock(&lock);
        return status;
    }
    struct timespec deadline{};
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += timeout_ms / 1000;
    deadline.tv_nsec += static_cast<long>(timeout_ms % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) { ++deadline.tv_sec; deadline.tv_nsec -= 1000000000L; }
    pthread_mutex_lock(&lock);
    for (;;) {
        for (uint32_t i = 0; i < queue_size; ++i) if (queue[i].focus == requested) {
            *out = queue[i];
            memmove(&queue[i], &queue[i + 1], (queue_size - i - 1) * sizeof(queue[0]));
            --queue_size;
            log_delivery_locked(*out);
            pthread_mutex_unlock(&lock);
            return 0;
        }
        if (!started || stopping) { pthread_mutex_unlock(&lock); return -2; }
        int result = pthread_cond_timedwait(&changed, &lock, &deadline);
        if (result == ETIMEDOUT) { pthread_mutex_unlock(&lock); return 1; }
        if (result != 0) { pthread_mutex_unlock(&lock); return -1; }
    }
}

extern "C" int32_t orbis_input_get_state(orbis_input_state *out) {
    if (out == nullptr || out->struct_size < sizeof(*out) || out->abi_version != ORBIS_INPUT_ABI_VERSION) return -1;
    pthread_mutex_lock(&lock);
    *out = state;
    pthread_mutex_unlock(&lock);
    return 0;
}
