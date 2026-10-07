#define _POSIX_C_SOURCE 200809L
#include <orbis/libkernel.h>
#include "orbis_runtime.h"
#include <orbis/UserService.h>
#include <orbis/VideoOut.h>

#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "orbis_display_service.h"
#include "orbis_input_service.h"

namespace {
constexpr uint32_t kWidth = ORBIS_DISPLAY_WIDTH;
constexpr uint32_t kHeight = ORBIS_DISPLAY_HEIGHT;
constexpr size_t kPixelCount = ORBIS_DISPLAY_PIXEL_COUNT;
constexpr size_t kFrameBytes = kPixelCount * sizeof(uint32_t);
constexpr size_t kMemoryAlignment = 0x200000;
constexpr int kBufferCount = 2;
constexpr uint64_t kFramePeriodNs = 1000000000ULL / 60ULL;
constexpr uint32_t kLogLimit = 8;

pthread_mutex_t state_lock = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t lifecycle_lock = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t state_changed = PTHREAD_COND_INITIALIZER;

struct MailboxSlot {
    uint32_t *pixels;
    uint64_t sequence;
    bool pending;
};

MailboxSlot mailbox[2]{};
uint32_t *snapshot;
uint32_t *scanout[kBufferCount]{};
void *video_memory;
off_t direct_memory_offset;
size_t direct_memory_size;
bool direct_memory_allocated;
int video_handle = -1;
int registration_group = -1;
bool registration_succeeded;
bool resources_retained;
bool started;
bool stop_requested;
bool desktop_enabled;
int last_presented_mode = -1;
bool snapshot_is_desktop;
uint64_t snapshot_sequence;
bool console_dirty = true;
bool frame_dirty;
bool cursor_dirty;
int32_t cursor_x;
int32_t cursor_y;
bool cursor_visible;
bool thread_created;
pthread_t presenter_thread;
bool pending_flip;
int pending_buffer = -1;
int current_buffer = -1;
int64_t pending_flip_arg;
int64_t next_flip_arg = 1;
uint64_t last_accepted_sequence;
bool have_accepted_sequence;
uint32_t diagnostic_count;
int32_t last_diagnostic;
orbis_display_console_render_fn console_renderer;
void *console_renderer_context;

void diagnostic(const char *stage, int32_t result) {
    if (diagnostic_count >= kLogLimit && result == last_diagnostic) return;
    if (diagnostic_count >= kLogLimit) return;
    char message[160];
    snprintf(message, sizeof(message),
             "orbis-java-display: %s result=0x%08x\n", stage,
             static_cast<unsigned int>(result));
    sceKernelDebugOutText(0, message);
    last_diagnostic = result;
    ++diagnostic_count;
}

uint64_t monotonic_ns() {
    struct timespec now{};
    if (clock_gettime(ORBIS_CLOCK_MONOTONIC, &now) != 0) return 0;
    return static_cast<uint64_t>(now.tv_sec) * 1000000000ULL +
           static_cast<uint64_t>(now.tv_nsec);
}

void sleep_us(uint32_t usec) {
    sceKernelUsleep(usec);
}

void sleep_until_ns(uint64_t deadline) {
    for (;;) {
        const uint64_t now = monotonic_ns();
        if (now >= deadline || now == 0) return;
        const uint64_t remaining_us = (deadline - now + 999ULL) / 1000ULL;
        sleep_us(static_cast<uint32_t>(remaining_us > 5000 ? 5000 : remaining_us));
    }
}

int choose_output_buffer_locked() {
    for (int i = 0; i < kBufferCount; ++i) {
        if (i != current_buffer && (!pending_flip || i != pending_buffer))
            return i;
    }
    return -1;
}

bool copy_latest_frame_locked() {
    int newest = -1;
    for (int i = 0; i < 2; ++i) {
        if (mailbox[i].pending &&
            (newest < 0 || mailbox[i].sequence > mailbox[newest].sequence))
            newest = i;
    }
    if (newest < 0) return false;
    if (snapshot_is_desktop &&
        snapshot_sequence == mailbox[newest].sequence) {
        // Keep the newest accepted slot as the recovery image when the
        // console takes focus and reuses the private snapshot.
        for (int i = 0; i < 2; ++i) {
            if (i != newest && mailbox[i].pending &&
                mailbox[i].sequence <= mailbox[newest].sequence)
                mailbox[i].pending = false;
        }
        return false;
    }
    memcpy(snapshot, mailbox[newest].pixels, kFrameBytes);
    snapshot_is_desktop = true;
    snapshot_sequence = mailbox[newest].sequence;
    for (int i = 0; i < 2; ++i) {
        if (i != newest && mailbox[i].pending &&
            mailbox[i].sequence <= mailbox[newest].sequence)
            mailbox[i].pending = false;
    }
    frame_dirty = true;
    return true;
}

// Canonical straight ARGB is composited over opaque black, then red and blue
// are exchanged for the A8B8G8R8 scanout format used by this target.
uint32_t argb_to_a8b8g8r8(uint32_t argb) {
    const uint32_t a = (argb >> 24) & 0xff;
    const uint32_t r = ((argb >> 16) & 0xff) * a / 255;
    const uint32_t g = ((argb >> 8) & 0xff) * a / 255;
    const uint32_t b = (argb & 0xff) * a / 255;
    return 0xff000000U | (b << 16) | (g << 8) | r;
}

// Cursor is drawn directly into the selected output buffer so the canonical
// base snapshot remains untouched and moving the cursor cannot leave trails.
void draw_cursor(uint32_t *output, int32_t x, int32_t y, bool visible) {
    static const char *const shape[] = {
        "X...........", "XX..........", "XOX.........", "XOOX........",
        "XOOOX.......", "XOOOOX......", "XOOOOOX.....", "XOOOOOOX....",
        "XOOOOXXXXX..", "XOOXOX.......", "XOX.XOX......", "XX..XOX......",
        "X....XOX.....", ".....XOX.....", "......XX.....", "............."
    };
    if (!visible) return;
    for (int cy = 0; cy < 16; ++cy) {
        for (int cx = 0; cx < 12; ++cx) {
            const char pixel = shape[cy][cx];
            if (pixel != 'X' && pixel != 'O') continue;
            const int32_t px = x + cx;
            const int32_t py = y + cy;
            if (px < 0 || py < 0 || px >= static_cast<int32_t>(kWidth) ||
                py >= static_cast<int32_t>(kHeight)) continue;
            // The opaque one-bit cursor palette is represented in scanout
            // channel order (black outline, white fill).
            output[static_cast<size_t>(py) * kWidth + px] =
                pixel == 'X' ? 0xff000000U : 0xffffffffU;
        }
    }
}

bool compose_locked(int buffer, bool use_console, bool render_console,
                    orbis_display_console_render_fn *renderer_out,
                    void **context_out) {
    bool changed = false;
    if (use_console && render_console && console_renderer != nullptr) {
        *renderer_out = console_renderer;
        *context_out = console_renderer_context;
        console_dirty = false;
        frame_dirty = false;
        cursor_dirty = false;
        snapshot_is_desktop = false;
        return false; // renderer must run outside state_lock
    }
    if (use_console && console_dirty) {
        // An absent renderer produces a deterministic opaque-black console.
        for (size_t i = 0; i < kPixelCount; ++i) snapshot[i] = 0xff000000U;
        console_dirty = false;
        changed = true;
    }
    if (frame_dirty) {
        frame_dirty = false;
        changed = true;
    }
    if (cursor_dirty) {
        cursor_dirty = false;
        changed = true;
    }
    if (!changed) return false;
    uint32_t *output = scanout[buffer];
    for (size_t i = 0; i < kPixelCount; ++i)
        output[i] = argb_to_a8b8g8r8(snapshot[i]);
    draw_cursor(output, cursor_x, cursor_y, cursor_visible);
    return true;
}

void *presenter_main(void *) {
    uint64_t next_frame_ns = monotonic_ns();
    for (;;) {
        // Input focus is read before taking state_lock. This keeps focus native
        // and responsive during Java pauses without creating lock inversion.
        orbis_input_state input_state{};
        input_state.abi_version = ORBIS_INPUT_ABI_VERSION;
        input_state.struct_size = sizeof(input_state);
        const bool have_input_focus = orbis_input_get_state(&input_state) == 0 &&
            input_state.focus <= ORBIS_INPUT_FOCUS_DESKTOP;
        pthread_mutex_lock(&state_lock);
        if (stop_requested) {
            pthread_mutex_unlock(&state_lock);
            break;
        }
        const bool is_pending = pending_flip;
        const int64_t expected_arg = pending_flip_arg;
        const int expected_buffer = pending_buffer;
        pthread_mutex_unlock(&state_lock);

        if (is_pending) {
            OrbisVideoOutFlipStatus status{};
            const int32_t result = sceVideoOutGetFlipStatus(video_handle, &status);
            if (result == 0 && status.flipArg == expected_arg) {
                pthread_mutex_lock(&state_lock);
                if (pending_flip && pending_flip_arg == expected_arg) {
                    pending_flip = false;
                    pending_buffer = -1;
                    current_buffer = expected_buffer;
                    pthread_cond_broadcast(&state_changed);
                }
                pthread_mutex_unlock(&state_lock);
            } else if (result < 0) {
                pthread_mutex_lock(&state_lock);
                diagnostic("get-flip-status", result);
                pthread_mutex_unlock(&state_lock);
            }
            // A timed-out or unrecognized flip keeps pending_buffer reserved.
            sleep_us(2000);
            continue;
        }

        const uint64_t now = monotonic_ns();
        if (now != 0 && now < next_frame_ns) {
            sleep_until_ns(next_frame_ns);
            continue;
        }

        pthread_mutex_lock(&state_lock);
        if (stop_requested) {
            pthread_mutex_unlock(&state_lock);
            break;
        }
        const bool use_console = !desktop_enabled || !have_input_focus ||
            input_state.focus == ORBIS_INPUT_FOCUS_CONSOLE;
        const int mode = use_console ? 0 : 1;
        if (last_presented_mode != mode) {
            if (use_console) console_dirty = true;
            else frame_dirty = true;
            last_presented_mode = mode;
        }
        if (!use_console && desktop_enabled) {
            copy_latest_frame_locked();
            if (!have_accepted_sequence && !snapshot_is_desktop) {
                for (size_t i = 0; i < kPixelCount; ++i)
                    snapshot[i] = 0xff000000U;
                snapshot_is_desktop = true;
                frame_dirty = true;
            }
        }
        const int output_buffer = choose_output_buffer_locked();
        orbis_display_console_render_fn renderer = nullptr;
        void *renderer_context = nullptr;
        bool composed = false;
        if (output_buffer >= 0) {
            composed = compose_locked(output_buffer, use_console,
                                      use_console && console_dirty,
                                      &renderer, &renderer_context);
        }
        if (renderer != nullptr) {
            // Rendering is deliberately outside state_lock. The callback must
            // not re-enter this service; it fills the base snapshot in place.
            uint32_t *target = snapshot;
            pthread_mutex_unlock(&state_lock);
            const int32_t render_result = renderer(target, kWidth, kHeight,
                                                    kWidth, renderer_context);
            pthread_mutex_lock(&state_lock);
            if (render_result == 0 && !stop_requested && output_buffer >= 0) {
                snapshot_is_desktop = false;
                composed = true;
                for (size_t i = 0; i < kPixelCount; ++i)
                    scanout[output_buffer][i] = argb_to_a8b8g8r8(snapshot[i]);
                draw_cursor(scanout[output_buffer], cursor_x, cursor_y,
                            cursor_visible);
            } else {
                console_dirty = true;
                if (render_result < 0) diagnostic("console-render", render_result);
            }
        }
        if (!composed || stop_requested || output_buffer < 0) {
            pthread_mutex_unlock(&state_lock);
            sleep_us(5000);
            continue;
        }

        // Composition (including the unlocked console callback) can overlap
        // an input focus change. Refresh focus without state_lock, then compare
        // against the mode used for this image before publishing a flip.
        pthread_mutex_unlock(&state_lock);
        orbis_input_state fresh_input_state{};
        fresh_input_state.abi_version = ORBIS_INPUT_ABI_VERSION;
        fresh_input_state.struct_size = sizeof(fresh_input_state);
        const bool have_fresh_focus =
            orbis_input_get_state(&fresh_input_state) == 0 &&
            fresh_input_state.focus <= ORBIS_INPUT_FOCUS_DESKTOP;
        pthread_mutex_lock(&state_lock);
        const bool fresh_use_console = !desktop_enabled || !have_fresh_focus ||
            fresh_input_state.focus == ORBIS_INPUT_FOCUS_CONSOLE;
        if (stop_requested || fresh_use_console != use_console) {
            if (fresh_use_console) console_dirty = true;
            else frame_dirty = true;
            pthread_mutex_unlock(&state_lock);
            sleep_us(1000);
            continue;
        }
        if (next_flip_arg == INT64_MAX) {
            diagnostic("flip-sequence-exhausted", -1);
            pthread_mutex_unlock(&state_lock);
            sleep_us(5000);
            continue;
        }
        const int64_t flip_arg = next_flip_arg++;
        pending_flip = true;
        pending_buffer = output_buffer;
        pending_flip_arg = flip_arg;
        pthread_mutex_unlock(&state_lock);

        const int32_t submit_result = sceVideoOutSubmitFlip(
            video_handle, output_buffer, ORBIS_VIDEO_OUT_FLIP_VSYNC, flip_arg);
        if (submit_result < 0) {
            // The SDK declaration does not establish whether an error can
            // follow partial submission. Keep this buffer reserved and poll
            // for the exact flip identity instead of risking a scanout write.
            pthread_mutex_lock(&state_lock);
            diagnostic("submit-flip", submit_result);
            pthread_mutex_unlock(&state_lock);
        }
        next_frame_ns = monotonic_ns() + kFramePeriodNs;
    }
    return nullptr;
}

bool release_unregistered_memory() {
    if (video_memory != nullptr) {
        if (sceKernelMunmap(video_memory, direct_memory_size) < 0) return false;
        video_memory = nullptr;
    }
    if (direct_memory_allocated) {
        if (sceKernelReleaseDirectMemory(direct_memory_offset,
                                         direct_memory_size) < 0) return false;
        direct_memory_allocated = false;
        direct_memory_size = 0;
    }
    return true;
}

bool close_unregistered_resources() {
    // This path is used only before successful buffer registration, when no
    // scanout buffer was handed to VideoOut.
    if (video_handle >= 0) {
        const int32_t close_result = sceVideoOutClose(video_handle);
        if (close_result < 0) diagnostic("close-unregistered", close_result);
        video_handle = -1;
    }
    const bool released = release_unregistered_memory();
    if (!released) {
        diagnostic("release-unregistered-memory", -1);
        resources_retained = true;
    }
    return released;
}

int32_t initialize_video_resources() {
    video_handle = sceVideoOutOpen(ORBIS_VIDEO_USER_MAIN,
                                   ORBIS_VIDEO_OUT_BUS_MAIN, 0, nullptr);
    if (video_handle < 0) {
        diagnostic("open", video_handle);
        return -1;
    }
    direct_memory_size = (kFrameBytes * kBufferCount + kMemoryAlignment - 1) /
                         kMemoryAlignment * kMemoryAlignment;
    const int32_t alloc_result = sceKernelAllocateDirectMemory(
        0, sceKernelGetDirectMemorySize(), direct_memory_size,
        kMemoryAlignment, 3, &direct_memory_offset);
    if (alloc_result < 0) {
        diagnostic("allocate-direct-memory", alloc_result);
        close_unregistered_resources();
        return -1;
    }
    direct_memory_allocated = true;
    const int32_t map_result = sceKernelMapDirectMemory(
        &video_memory, direct_memory_size, 0x33, 0, direct_memory_offset,
        kMemoryAlignment);
    if (map_result < 0) {
        diagnostic("map-direct-memory", map_result);
        close_unregistered_resources();
        return -1;
    }
    scanout[0] = static_cast<uint32_t *>(video_memory);
    scanout[1] = reinterpret_cast<uint32_t *>(
        static_cast<uint8_t *>(video_memory) + kFrameBytes);
    for (int i = 0; i < kBufferCount; ++i)
        for (size_t p = 0; p < kPixelCount; ++p) scanout[i][p] = 0xff000000U;

    OrbisVideoOutBufferAttribute attr{};
    sceVideoOutSetBufferAttribute(&attr,
        ORBIS_VIDEO_OUT_PIXEL_FORMAT_A8B8G8R8_SRGB, 1,
        ORBIS_VIDEO_OUT_ASPECT_RATIO_16_9, kWidth, kHeight, kWidth);
    const int32_t register_result = sceVideoOutRegisterBuffers(
        video_handle, 0, reinterpret_cast<void *const *>(scanout),
        kBufferCount, &attr);
    if (register_result < 0) {
        diagnostic("register-buffers", register_result);
        // A failed registration is not evidence that the implementation did
        // not retain one of the pointers. Preserve the mapping on uncertainty.
        const int32_t close_result = sceVideoOutClose(video_handle);
        if (close_result < 0) diagnostic("close-after-register-failure", close_result);
        video_handle = -1;
        resources_retained = true;
        return -1;
    }
    // The API's return value is the attribute/group identifier consumed by
    // sceVideoOutUnregisterBuffers; it is not one of the scanout buffer IDs.
    registration_group = register_result;
    registration_succeeded = true;
    current_buffer = 0; // reserve a possible initial scanout buffer
    sceVideoOutSetFlipRate(video_handle, ORBIS_VIDEO_OUT_FLIP_60HZ);
    return 0;
}
} // namespace

extern "C" int32_t orbis_display_set_console_renderer(
    orbis_display_console_render_fn renderer, void *context) {
    pthread_mutex_lock(&lifecycle_lock);
    pthread_mutex_lock(&state_lock);
    if (started) {
        pthread_mutex_unlock(&state_lock);
        pthread_mutex_unlock(&lifecycle_lock);
        return -1;
    }
    console_renderer = renderer;
    console_renderer_context = renderer == nullptr ? nullptr : context;
    console_dirty = true;
    pthread_mutex_unlock(&state_lock);
    pthread_mutex_unlock(&lifecycle_lock);
    return 0;
}

extern "C" int32_t orbis_display_start(void) {
    pthread_mutex_lock(&lifecycle_lock);
    pthread_mutex_lock(&state_lock);
    if (started) {
        pthread_mutex_unlock(&state_lock);
        pthread_mutex_unlock(&lifecycle_lock);
        return 0;
    }
    if (resources_retained || registration_succeeded) {
        pthread_mutex_unlock(&state_lock);
        pthread_mutex_unlock(&lifecycle_lock);
        return -1;
    }
    pthread_mutex_unlock(&state_lock);

    snapshot = static_cast<uint32_t *>(malloc(kFrameBytes));
    mailbox[0].pixels = static_cast<uint32_t *>(malloc(kFrameBytes));
    mailbox[1].pixels = static_cast<uint32_t *>(malloc(kFrameBytes));
    if (snapshot == nullptr || mailbox[0].pixels == nullptr ||
        mailbox[1].pixels == nullptr) {
        free(snapshot);
        free(mailbox[0].pixels);
        free(mailbox[1].pixels);
        snapshot = mailbox[0].pixels = mailbox[1].pixels = nullptr;
        pthread_mutex_unlock(&lifecycle_lock);
        return -1;
    }
    for (size_t i = 0; i < kPixelCount; ++i) snapshot[i] = 0xff000000U;
    if (initialize_video_resources() < 0) {
        free(snapshot);
        free(mailbox[0].pixels);
        free(mailbox[1].pixels);
        snapshot = mailbox[0].pixels = mailbox[1].pixels = nullptr;
        pthread_mutex_unlock(&lifecycle_lock);
        return -1;
    }

    pthread_mutex_lock(&state_lock);
    stop_requested = false;
    console_dirty = true;
    frame_dirty = true;
    cursor_dirty = true;
    pending_flip = false;
    pending_buffer = -1;
    last_presented_mode = -1;
    snapshot_is_desktop = false;
    pthread_mutex_unlock(&state_lock);
    const int create_result = pthread_create(&presenter_thread, nullptr,
                                             presenter_main, nullptr);
    if (create_result != 0) {
        // Once registered, the target's ownership release semantics are not
        // independently established. Close what we can and retain the direct
        // memory mapping instead of risking freeing scanout storage.
        const int32_t unregister_result = sceVideoOutUnregisterBuffers(
            video_handle, registration_group);
        if (unregister_result < 0)
            diagnostic("unregister-after-thread-failure", unregister_result);
        const int32_t close_result = sceVideoOutClose(video_handle);
        if (close_result < 0) diagnostic("close-after-thread-failure", close_result);
        resources_retained = true;
        video_handle = -1;
        free(snapshot);
        free(mailbox[0].pixels);
        free(mailbox[1].pixels);
        snapshot = mailbox[0].pixels = mailbox[1].pixels = nullptr;
        pthread_mutex_unlock(&lifecycle_lock);
        return -1;
    }
    thread_created = true;
    pthread_mutex_lock(&state_lock);
    started = true;
    pthread_mutex_unlock(&state_lock);
    pthread_mutex_unlock(&lifecycle_lock);
    return 0;
}

extern "C" void orbis_display_stop(void) {
    pthread_mutex_lock(&lifecycle_lock);
    pthread_mutex_lock(&state_lock);
    if (!started && !thread_created) {
        pthread_mutex_unlock(&state_lock);
        pthread_mutex_unlock(&lifecycle_lock);
        return;
    }
    started = false; // reject new submissions before waking the presenter
    stop_requested = true;
    pthread_cond_broadcast(&state_changed);
    const bool join_thread = thread_created;
    pthread_mutex_unlock(&state_lock);
    if (join_thread) pthread_join(presenter_thread, nullptr);
    thread_created = false;

    if (registration_succeeded) {
        const int32_t unregister_result = sceVideoOutUnregisterBuffers(
            video_handle, registration_group);
        if (unregister_result < 0)
            diagnostic("unregister", unregister_result);
        const int32_t close_result = sceVideoOutClose(video_handle);
        if (close_result < 0) diagnostic("close", close_result);
        // Local declarations establish signatures only. Available homebrew
        // call sites do not establish that successful unregister/close ends
        // scanout ownership before return. Conservatively retain both mapping
        // and direct-memory allocation for process lifetime on every outcome.
        resources_retained = true;
        video_handle = -1;
    } else {
        close_unregistered_resources();
    }
    free(snapshot);
    free(mailbox[0].pixels);
    free(mailbox[1].pixels);
    snapshot = mailbox[0].pixels = mailbox[1].pixels = nullptr;
    pthread_mutex_unlock(&lifecycle_lock);
}

extern "C" int32_t orbis_display_set_desktop_enabled(int32_t enabled) {
    if (enabled != 0 && enabled != 1) return -1;
    pthread_mutex_lock(&state_lock);
    desktop_enabled = enabled != 0;
    if (desktop_enabled) frame_dirty = true;
    else console_dirty = true;
    pthread_cond_broadcast(&state_changed);
    pthread_mutex_unlock(&state_lock);
    return 0;
}

extern "C" int32_t orbis_display_submit_argb(const uint32_t *pixels,
                                              size_t count, uint32_t width,
                                              uint32_t height,
                                              uint32_t stride_pixels,
                                              uint64_t sequence) {
    if (pixels == nullptr || width != kWidth || height != kHeight ||
        stride_pixels != kWidth || count != kPixelCount) return -1;
    pthread_mutex_lock(&state_lock);
    if (!started || stop_requested ||
        (have_accepted_sequence && sequence <= last_accepted_sequence)) {
        pthread_mutex_unlock(&state_lock);
        return -1;
    }
    int slot = -1;
    for (int i = 0; i < 2; ++i) {
        if (!mailbox[i].pending) {
            slot = i;
            break;
        }
    }
    if (slot < 0)
        slot = mailbox[0].sequence <= mailbox[1].sequence ? 0 : 1;
    memcpy(mailbox[slot].pixels, pixels, kFrameBytes);
    mailbox[slot].sequence = sequence;
    mailbox[slot].pending = true;
    last_accepted_sequence = sequence;
    have_accepted_sequence = true;
    frame_dirty = true;
    pthread_cond_broadcast(&state_changed);
    pthread_mutex_unlock(&state_lock);
    return 0;
}

extern "C" int32_t orbis_display_set_cursor(int32_t x, int32_t y,
                                            int32_t visible) {
    pthread_mutex_lock(&state_lock);
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    if (x >= static_cast<int32_t>(kWidth)) x = kWidth - 1;
    if (y >= static_cast<int32_t>(kHeight)) y = kHeight - 1;
    const bool new_visible = visible != 0;
    if (cursor_x != x || cursor_y != y || cursor_visible != new_visible) {
        cursor_x = x;
        cursor_y = y;
        cursor_visible = new_visible;
        cursor_dirty = true;
        pthread_cond_broadcast(&state_changed);
    }
    pthread_mutex_unlock(&state_lock);
    return 0;
}

extern "C" void orbis_display_mark_console_dirty(void) {
    pthread_mutex_lock(&state_lock);
    console_dirty = true;
    pthread_cond_broadcast(&state_changed);
    pthread_mutex_unlock(&state_lock);
}
