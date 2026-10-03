/* SPDX-License-Identifier: BSD-3-Clause
 * Orbis no-device OpenAL provider. Original compatibility implementation; no OpenAL code copied.
 */
#include <stddef.h>
#include <string.h>
#include <stdio.h>

typedef char ALCchar;
typedef int ALCenum;
typedef int ALCint;
typedef int ALCsizei;
typedef unsigned char ALCboolean;
typedef struct ALCdevice_struct ALCdevice;
typedef struct ALCcontext_struct ALCcontext;
#define ALC_INVALID_DEVICE 0xA001
static _Thread_local ALCenum last_error;
static int warned;
static void unavailable(void) {
    if (!warned) { warned = 1; fputs("[orbis-openal] audio device unavailable; sound disabled\n", stderr); }
}
ALCdevice *alcOpenDevice(const ALCchar *name) { (void)name; unavailable(); last_error = ALC_INVALID_DEVICE; return NULL; }
ALCboolean alcCloseDevice(ALCdevice *device) { return device == NULL; }
ALCcontext *alcCreateContext(ALCdevice *device, const ALCint *attributes) { (void)device; (void)attributes; last_error = ALC_INVALID_DEVICE; return NULL; }
ALCboolean alcMakeContextCurrent(ALCcontext *context) { (void)context; return 0; }
void alcProcessContext(ALCcontext *context) { (void)context; }
void alcSuspendContext(ALCcontext *context) { (void)context; }
void alcDestroyContext(ALCcontext *context) { (void)context; }
ALCcontext *alcGetCurrentContext(void) { return NULL; }
ALCdevice *alcGetContextsDevice(ALCcontext *context) { (void)context; return NULL; }
ALCboolean alcIsExtensionPresent(ALCdevice *device, const ALCchar *name) { (void)device; (void)name; return 0; }
ALCenum alcGetEnumValue(ALCdevice *device, const ALCchar *name) { (void)device; (void)name; return 0; }
ALCenum alcGetError(ALCdevice *device) { (void)device; ALCenum result = last_error; last_error = 0; return result; }
const ALCchar *alcGetString(ALCdevice *device, ALCenum param) { (void)device; (void)param; return NULL; }
void alcGetIntegerv(ALCdevice *device, ALCenum param, ALCsizei count, ALCint *values) {
    (void)device; (void)param;
    if (values && count > 0) for (ALCsizei i = 0; i < count; ++i) values[i] = 0;
}
void *alcGetProcAddress(ALCdevice *device, const ALCchar *name) {
    (void)device;
    if (!name) return NULL;
#define PROC(symbol) if (strcmp(name, #symbol) == 0) return (void *)symbol
    PROC(alcOpenDevice); PROC(alcCloseDevice); PROC(alcCreateContext); PROC(alcMakeContextCurrent);
    PROC(alcProcessContext); PROC(alcSuspendContext); PROC(alcDestroyContext);
    PROC(alcGetCurrentContext); PROC(alcGetContextsDevice); PROC(alcIsExtensionPresent);
    PROC(alcGetProcAddress); PROC(alcGetEnumValue); PROC(alcGetError); PROC(alcGetString);
    PROC(alcGetIntegerv);
#undef PROC
    return NULL;
}
void *alGetProcAddress(const char *name) { (void)name; return NULL; }
