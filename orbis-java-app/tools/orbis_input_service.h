#ifndef ORBIS_INPUT_SERVICE_H
#define ORBIS_INPUT_SERVICE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ORBIS_INPUT_ABI_VERSION 1u
#define ORBIS_INPUT_KEY_BYTES 32u
#define ORBIS_INPUT_QUEUE_CAPACITY 256u

typedef enum orbis_input_focus {
    ORBIS_INPUT_FOCUS_CONSOLE = 0,
    ORBIS_INPUT_FOCUS_DESKTOP = 1
} orbis_input_focus;

typedef enum orbis_input_event_type {
    ORBIS_INPUT_EVENT_RESET = 0,
    ORBIS_INPUT_EVENT_KEY_DOWN = 1,
    ORBIS_INPUT_EVENT_KEY_UP = 2,
    ORBIS_INPUT_EVENT_TEXT = 3,
    ORBIS_INPUT_EVENT_MOTION = 4,
    ORBIS_INPUT_EVENT_BUTTON_DOWN = 5,
    ORBIS_INPUT_EVENT_BUTTON_UP = 6,
    ORBIS_INPUT_EVENT_WHEEL = 7,
    ORBIS_INPUT_EVENT_MODIFIERS = 8,
    ORBIS_INPUT_EVENT_FOCUS = 9
} orbis_input_event_type;

typedef struct orbis_input_event {
    uint32_t abi_version;
    uint32_t struct_size;
    uint32_t type;
    uint32_t focus;
    uint64_t monotonic_us;
    uint64_t reset_generation;
    uint32_t raw_key;
    uint32_t unicode_scalar;
    uint32_t modifiers;
    int32_t x;
    int32_t y;
    uint32_t button;
    int32_t wheel_delta;
    uint8_t pressed_keys[ORBIS_INPUT_KEY_BYTES];
    uint32_t pressed_buttons;
} orbis_input_event;

typedef struct orbis_input_state {
    uint32_t abi_version;
    uint32_t struct_size;
    uint32_t focus;
    uint32_t modifiers;
    uint64_t reset_generation;
    int32_t x;
    int32_t y;
    uint8_t pressed_keys[ORBIS_INPUT_KEY_BYTES];
    uint32_t pressed_buttons;
} orbis_input_state;

int32_t orbis_input_start(void);
void orbis_input_stop(void);
int32_t orbis_input_set_desktop_enabled(int32_t enabled);
int32_t orbis_input_set_focus(orbis_input_focus focus);
/* Returns 0 for an event, 1 on timeout, and a negative value when stopped/error.
 * Reads only events owned by requested_focus; each event records that owner. */
int32_t orbis_input_read_event(orbis_input_focus requested_focus,
                               orbis_input_event *out, uint32_t timeout_ms);
int32_t orbis_input_get_state(orbis_input_state *out);
/* F12 switches console/desktop focus while enabled (default). Modes without a
 * visible console disable it so the application keeps input. */
int32_t orbis_input_set_focus_toggle_enabled(int32_t enabled);
/* Moves the pointer reference (clamped to 1280x720) without a MOTION event;
 * relative-motion consumers re-centre with it. */
int32_t orbis_input_set_pointer(int32_t x, int32_t y);
/* Bounded UART dumps of raw keyboard samples and keyboard transitions (debug mode). */
void orbis_input_set_diagnostics(int32_t enabled);
/* Also appends the diagnostics, stamped in ms, to this file (written by the collector). */
void orbis_input_set_diagnostics_log(const char *path);

#ifdef __cplusplus
}
#endif
#endif
