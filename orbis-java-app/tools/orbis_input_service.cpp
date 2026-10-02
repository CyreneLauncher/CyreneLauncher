#define _POSIX_C_SOURCE 200809L
#include "orbis_input_service.h"
#include "orbis_mouse_abi.h"
#include "orbis_display_service.h"

#include <orbis/Keyboard.h>
#include <orbis/Sysmodule.h>
#include <orbis/UserService.h>
#include <orbis/libkernel.h>

#include <pthread.h>
#include <errno.h>
#include <stdio.h>
#include <time.h>
#include <string.h>

namespace {
pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
pthread_t collector;
bool started;
bool stopping;
bool lifecycle_busy;
bool desktop_enabled;
bool focus_toggle_enabled = true;
bool swallowed_keys[256];
int keyboard_handle = -1;
int mouse_handle = -1;
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
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000u +
           static_cast<uint64_t>(ts.tv_nsec / 1000);
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

void enqueue_locked(const orbis_input_event &event) {
    if (event.type == ORBIS_INPUT_EVENT_MOTION && queue_size != 0 &&
        queue[queue_size - 1].type == ORBIS_INPUT_EVENT_MOTION &&
        queue[queue_size - 1].focus == event.focus) {
        queue[queue_size - 1] = event;
        pthread_cond_broadcast(&changed);
        return;
    }
    if (queue_size == ORBIS_INPUT_QUEUE_CAPACITY) {
        reset_overflow_locked();
        return;
    }
    queue[queue_size++] = event;
    pthread_cond_broadcast(&changed);
}

void transition_locked(uint32_t type, uint32_t raw_key, uint32_t scalar) {
    orbis_input_event event{};
    snapshot_event(&event, type);
    event.raw_key = raw_key;
    event.unicode_scalar = scalar;
    enqueue_locked(event);
}

void *collect(void *) {
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
                sceKernelDebugOutText(0, message);
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
                sceKernelDebugOutText(0, message);
                last_mouse_error = open_result;
                ++mouse_error_diagnostics;
            }
            mhandle = mouse_handle;
        }
        OrbisKeyboardData sample{};
        int32_t result = 0;
        if (handle >= 0)
            result = sceKeyboardReadState(handle, &sample);
        if (result < 0) {
            keyboard_retry_after = now_us() + 1000000;
            sceKeyboardClose(handle);
            keyboard_handle = -1;
            pthread_mutex_lock(&lock);
            reset_keyboard_locked();
            pthread_mutex_unlock(&lock);
            if (result != last_keyboard_error && keyboard_error_diagnostics < 8) {
                char message[128];
                snprintf(message, sizeof(message),
                         "orbis-java-input: keyboard read error 0x%08x\n",
                         static_cast<unsigned int>(result));
                sceKernelDebugOutText(0, message);
                last_keyboard_error = result;
                ++keyboard_error_diagnostics;
            }
        }
        bool keyboard_sample_valid = handle >= 0 && result >= 0;
        if (keyboard_sample_valid && (sample.nkeys < 0 || sample.nkeys > 32)) {
            pthread_mutex_lock(&lock);
            reset_keyboard_locked();
            pthread_mutex_unlock(&lock);
            keyboard_sample_valid = false;
        }
        bool refresh_cursor = false;
        if (keyboard_sample_valid) {
            uint16_t keys[32];
            for (int32_t i = 0; i < sample.nkeys; ++i) keys[i] = sample.keycodes[i];
            pthread_mutex_lock(&lock);
            uint32_t count = static_cast<uint32_t>(sample.nkeys);
            if (state.modifiers != sample.mods) {
                state.modifiers = sample.mods;
                transition_locked(ORBIS_INPUT_EVENT_MODIFIERS, 0, 0);
            }
            for (uint32_t i = 0; i < count; ++i) {
                uint16_t key = keys[i];
                if (key >= 256) continue;
                if (swallowed_keys[key]) continue;
                if (!key_down(key, prior_keys, prior_count)) {
                    uint64_t tick = now_us();
                    held_since[key] = tick;
                    repeat_due[key] = tick + 500000;
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
                    OrbisKeyboardKey2Char translated{};
                    if (sceKeyboardGetKey2Char(handle, false, sample.locks,
                            sample.mods, key, &translated) == 0 && translated.ok &&
                        translated.keycode >= 32 && translated.keycode <= 126) {
                        transition_locked(ORBIS_INPUT_EVENT_TEXT, key,
                                          static_cast<uint32_t>(translated.keycode));
                    }
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
                    held_since[key] = repeat_due[key] = 0;
                    state.pressed_keys[key >> 3] &= static_cast<uint8_t>(~(1u << (key & 7)));
                    transition_locked(ORBIS_INPUT_EVENT_KEY_UP, key, 0);
                }
            }
            /* Clear stale suppression only after the release loop has consumed it. */
            for (uint32_t key = 0; key < 256; ++key)
                if (swallowed_keys[key] && !key_down(static_cast<uint16_t>(key), keys, count))
                    swallowed_keys[key] = false;
            uint64_t tick = now_us();
            for (uint32_t i = 0; i < count; ++i) {
                uint16_t key = keys[i];
                if (key >= 256 || held_since[key] == 0 || tick < repeat_due[key]) continue;
                if (key == ORBIS_KEYBOARD_KEY_RETURN) continue;
                // Raw Backspace is an edit key regardless of firmware translation.
                if (key == ORBIS_KEYBOARD_KEY_BACKSPACE) {
                    transition_locked(ORBIS_INPUT_EVENT_TEXT, key, '\b');
                    repeat_due[key] = tick + 33000;
                    break;
                }
                OrbisKeyboardKey2Char translated{};
                if (sceKeyboardGetKey2Char(handle, false, sample.locks, sample.mods,
                        key, &translated) == 0 && translated.ok &&
                    translated.keycode >= 32 && translated.keycode <= 126) {
                    transition_locked(ORBIS_INPUT_EVENT_TEXT, key,
                                      static_cast<uint32_t>(translated.keycode));
                    repeat_due[key] = tick + 33000;
                    break;
                }
            }
            memcpy(prior_keys, keys, count * sizeof(keys[0]));
            prior_count = count;
            pthread_mutex_unlock(&lock);
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
                reset_overflow_locked();
                pthread_mutex_unlock(&lock);
                if (sample_count != last_mouse_error && mouse_error_diagnostics < 8) {
                    char message[128];
                    snprintf(message, sizeof(message),
                             "orbis-java-input: mouse read error 0x%08x\n",
                             static_cast<unsigned int>(sample_count));
                    sceKernelDebugOutText(0, message);
                    last_mouse_error = sample_count;
                    ++mouse_error_diagnostics;
                }
            }
            bool cursor_changed = false;
            if (sample_count > 0) {
                pthread_mutex_lock(&lock);
                for (int32_t i = 0; i < sample_count; ++i) {
                    const orbis_mouse_data_abi &sample = samples[i];
                    if (!sample.connected) {
                        if (prior_buttons != 0) {
                            prior_buttons = 0;
                            state.pressed_buttons = 0;
                            reset_overflow_locked();
                        }
                        continue;
                    }
                    int64_t nx = static_cast<int64_t>(mouse_x) + sample.x_axis;
                    int64_t ny = static_cast<int64_t>(mouse_y) + sample.y_axis;
                    if (nx < 0) nx = 0; if (nx > 1279) nx = 1279;
                    if (ny < 0) ny = 0; if (ny > 719) ny = 719;
                    mouse_x = static_cast<int32_t>(nx);
                    mouse_y = static_cast<int32_t>(ny);
                    state.x = mouse_x;
                    state.y = mouse_y;
                    cursor_changed |= sample.x_axis != 0 || sample.y_axis != 0;
                    uint32_t changed = prior_buttons ^ sample.buttons;
                    for (uint32_t bit = 0; bit < 32; ++bit) {
                        uint32_t mask = UINT32_C(1) << bit;
                        if ((changed & mask) == 0) continue;
                        if (sample.buttons & mask) state.pressed_buttons |= mask;
                        else state.pressed_buttons &= ~mask;
                        orbis_input_event event{};
                        snapshot_event(&event, (sample.buttons & mask)
                            ? ORBIS_INPUT_EVENT_BUTTON_DOWN : ORBIS_INPUT_EVENT_BUTTON_UP);
                        event.button = bit;
                        enqueue_locked(event);
                    }
                    prior_buttons = sample.buttons;
                    if (sample.x_axis != 0 || sample.y_axis != 0) {
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
        sceKernelUsleep(8000);
    }
    return nullptr;
}

int32_t initialize_devices() {
    int32_t result = 0;
    if (!user_service_initialized) {
        OrbisUserServiceInitializeParams params{};
        params.priority = ORBIS_KERNEL_PRIO_FIFO_LOWEST;
        result = sceUserServiceInitialize(&params);
        if (result < 0) return result;
        user_service_initialized = true;
    }
    result = sceUserServiceGetInitialUser(&user_id);
    if (result < 0 || user_id < 0) return result < 0 ? result : user_id;
    int32_t keyboard_result = open_keyboard();
    if (keyboard_result < 0) {
        char message[128];
        snprintf(message, sizeof(message),
                 "orbis-java-input: keyboard unavailable 0x%08x\n",
                 static_cast<unsigned int>(keyboard_result));
        sceKernelDebugOutText(0, message);
    }
    int32_t mouse_result = open_mouse();
    if (mouse_result < 0) {
        char message[128];
        snprintf(message, sizeof(message),
                 "orbis-java-input: mouse unavailable 0x%08x\n",
                 static_cast<unsigned int>(mouse_result));
        sceKernelDebugOutText(0, message);
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
    if (pthread_create(&collector, nullptr, collect, nullptr) != 0) {
        pthread_mutex_lock(&lock); started = false; pthread_mutex_unlock(&lock);
        if (keyboard_handle >= 0) sceKeyboardClose(keyboard_handle);
        keyboard_handle = -1;
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
