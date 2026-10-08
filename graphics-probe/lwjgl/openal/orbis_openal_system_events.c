/*
 * ALC_SOFT_system_events entry points for OpenAL Soft 1.23.1, which predates the extension (1.24).
 *
 * Minecraft 26.1's CallbackDeviceTracker calls alcEventIsSupportedSOFT without checking for the
 * extension; LWJGL resolves it by name from this library, and a missing symbol stops the game with
 * a NullPointerException. LWJGL resolves an extension's functions only when the extension string
 * lists it, so build_openal.py adds ALC_SOFT_system_events there. These answer that no event is
 * supported (true here: the AudioOut port has no device hot-plug), which the extension allows, so
 * Minecraft uses its polling device tracker. Values from the extension: ALC_EVENT_NOT_SUPPORTED_SOFT 0x19DA, ALC_INVALID_ENUM 0xA003.
 */
typedef int ALCenum;
typedef char ALCboolean;
typedef void (*ALCEVENTPROCTYPESOFT)(ALCenum eventType, ALCenum deviceType, void *device,
                                     int length, const char *message, void *userParam);

#define ALC_FALSE 0
#define ALC_EVENT_NOT_SUPPORTED_SOFT 0x19DA

#define ORBIS_EXPORT __attribute__((visibility("default")))

ORBIS_EXPORT ALCenum alcEventIsSupportedSOFT(ALCenum eventType, ALCenum deviceType) {
    (void)eventType;
    (void)deviceType;
    return ALC_EVENT_NOT_SUPPORTED_SOFT;
}

/* Enabling events that are not supported fails, as the extension specifies. */
ORBIS_EXPORT ALCboolean alcEventControlSOFT(int count, const ALCenum *events, ALCboolean enable) {
    (void)count;
    (void)events;
    (void)enable;
    return ALC_FALSE;
}

/* Kept for the caller's bookkeeping only: no event is ever delivered. */
ORBIS_EXPORT void alcEventCallbackSOFT(ALCEVENTPROCTYPESOFT callback, void *userParam) {
    (void)callback;
    (void)userParam;
}
