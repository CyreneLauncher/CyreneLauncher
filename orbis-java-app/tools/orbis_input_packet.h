#ifndef ORBIS_INPUT_PACKET_H
#define ORBIS_INPUT_PACKET_H
/* Java event packet, ABI 1: the layout runner.desktop.NativeDesktopBridge and
 * runner.gpu.NativeInput index. Words 16..23 hold the 256-bit pressed-key bitmap. */
#include "orbis_input_service.h"
#include <jni.h>

#define ORBIS_INPUT_PACKET_VERSION 1
#define ORBIS_INPUT_PACKET_WORDS 24

static inline void orbis_input_pack_event(const orbis_input_event *event,
                                          jint words[ORBIS_INPUT_PACKET_WORDS]) {
    const jint head[16] = {ORBIS_INPUT_PACKET_VERSION, ORBIS_INPUT_PACKET_WORDS,
        (jint)event->type, (jint)event->focus, (jint)event->monotonic_us,
        (jint)(event->monotonic_us >> 32), (jint)event->reset_generation,
        (jint)(event->reset_generation >> 32), (jint)event->raw_key,
        (jint)event->unicode_scalar, (jint)event->modifiers, event->x, event->y,
        (jint)event->button, event->wheel_delta, (jint)event->pressed_buttons};
    for (int i = 0; i < 16; ++i) words[i] = head[i];
    for (int i = 0; i < 8; ++i) {
        uint32_t value = 0;
        for (int b = 0; b < 4; ++b)
            value |= (uint32_t)event->pressed_keys[i * 4 + b] << (b * 8);
        words[16 + i] = (jint)value;
    }
}

/* Rejects events from another ABI, size or owner before Java sees them. */
static inline int orbis_input_event_valid(const orbis_input_event *event, uint32_t focus) {
    return event->abi_version == ORBIS_INPUT_ABI_VERSION && event->struct_size == sizeof(*event) &&
           event->type <= ORBIS_INPUT_EVENT_FOCUS && event->focus == focus;
}
#endif
