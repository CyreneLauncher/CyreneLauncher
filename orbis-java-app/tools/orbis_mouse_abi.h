#ifndef ORBIS_MOUSE_ABI_H
#define ORBIS_MOUSE_ABI_H

/* SPDX-FileCopyrightText: Copyright 2024-2026 shadPS4 Emulator Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Inferred Orbis ABI adapter from the pinned shadPS4 declarations. Hardware
 * ABI has not been independently verified. Do not include SDK Mouse.h: it is
 * a void/no-argument placeholder and conflicts with these exact prototypes. */
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct orbis_mouse_data_abi {
    uint64_t timestamp;
    uint8_t connected;
    uint8_t connected_padding[3];
    uint32_t buttons;
    int32_t x_axis;
    int32_t y_axis;
    int32_t wheel;
    int32_t tilt;
    uint8_t reserve[8];
} orbis_mouse_data_abi;

typedef struct orbis_mouse_open_param_abi {
    uint8_t flag;
    uint8_t reserve[7];
} orbis_mouse_open_param_abi;

int32_t sceMouseInit(void);
int32_t sceMouseOpen(int32_t user_id, int32_t type, int32_t index,
                     orbis_mouse_open_param_abi *param);
int32_t sceMouseRead(int32_t handle, orbis_mouse_data_abi *data, int32_t count);
int32_t sceMouseClose(int32_t handle);

#ifdef __cplusplus
}
static_assert(sizeof(orbis_mouse_data_abi) == 40, "mouse sample ABI size");
static_assert(offsetof(orbis_mouse_data_abi, timestamp) == 0, "timestamp offset");
static_assert(offsetof(orbis_mouse_data_abi, connected) == 8, "connected offset");
static_assert(offsetof(orbis_mouse_data_abi, buttons) == 12, "buttons offset");
static_assert(offsetof(orbis_mouse_data_abi, x_axis) == 16, "x offset");
static_assert(offsetof(orbis_mouse_data_abi, y_axis) == 20, "y offset");
static_assert(offsetof(orbis_mouse_data_abi, wheel) == 24, "wheel offset");
static_assert(offsetof(orbis_mouse_data_abi, tilt) == 28, "tilt offset");
static_assert(offsetof(orbis_mouse_data_abi, reserve) == 32, "reserve offset");
static_assert(sizeof(orbis_mouse_open_param_abi) == 8, "mouse open param size");
#endif
#endif
