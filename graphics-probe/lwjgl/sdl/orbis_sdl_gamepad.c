/*
 * The console's controller as an SDL virtual gamepad, for Controlify 3.5+ (Minecraft 26.1+), which
 * reads controllers only through SDL3. The runner owns the pad (the GLFW facade reads it once per
 * event poll); the compatibility mod passes that state here once per client tick, and SDL presents it
 * as a standard gamepad with the DualShock 4's vendor and product IDs, so Controlify shows PlayStation
 * glyphs. Called on the render thread, where Controlify also polls SDL.
 */
#include <SDL3/SDL.h>

static SDL_JoystickID orbis_id;
static SDL_Joystick *orbis_joystick;

/* Attached once the joystick subsystem is up; forgotten when SDL shuts it down (SDL frees it then). */
static bool orbis_attach(void)
{
    if (!SDL_WasInit(SDL_INIT_JOYSTICK)) {
        orbis_joystick = NULL;
        orbis_id = 0;
        return false;
    }
    if (orbis_joystick) return true;
    SDL_VirtualJoystickDesc desc;
    SDL_INIT_INTERFACE(&desc);
    desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
    desc.vendor_id = 0x054C;    /* Sony */
    desc.product_id = 0x09CC;   /* DualShock 4 (v2) */
    desc.naxes = SDL_GAMEPAD_AXIS_COUNT;
    desc.nbuttons = SDL_GAMEPAD_BUTTON_DPAD_RIGHT + 1;
    desc.button_mask = (1u << (SDL_GAMEPAD_BUTTON_DPAD_RIGHT + 1)) - 1;
    desc.axis_mask = (1u << SDL_GAMEPAD_AXIS_COUNT) - 1;
    desc.name = "PS4 Controller";
    orbis_id = SDL_AttachVirtualJoystick(&desc);
    if (!orbis_id) return false;
    orbis_joystick = SDL_OpenJoystick(orbis_id);
    if (!orbis_joystick) {
        SDL_DetachVirtualJoystick(orbis_id);
        orbis_id = 0;
        return false;
    }
    return true;
}

/*
 * buttons: bit n is SDL_GamepadButton n (south to dpad right); axes: left x/y, right x/y as SDL's
 * -32768..32767, triggers 0..32767. A disconnected pad detaches the gamepad. False while SDL's joystick
 * subsystem is not running or the gamepad cannot be attached (SDL_GetError says why).
 */
SDL_DECLSPEC bool SDLCALL orbis_sdl_gamepad_update(bool connected, Uint32 buttons, Sint16 left_x, Sint16 left_y,
                                                   Sint16 right_x, Sint16 right_y, Sint16 left_trigger,
                                                   Sint16 right_trigger)
{
    if (!connected) {
        if (orbis_joystick && SDL_WasInit(SDL_INIT_JOYSTICK)) {
            SDL_CloseJoystick(orbis_joystick);
            SDL_DetachVirtualJoystick(orbis_id);
        }
        orbis_joystick = NULL;
        orbis_id = 0;
        return true;
    }
    if (!orbis_attach()) return false;
    /* A virtual gamepad's trigger is a full-range joystick axis that SDL scales to 0..32767. */
    const Sint16 axes[SDL_GAMEPAD_AXIS_COUNT] = {left_x, left_y, right_x, right_y,
        (Sint16)(SDL_clamp(left_trigger, 0, 32767) * 2 - 32767), (Sint16)(SDL_clamp(right_trigger, 0, 32767) * 2 - 32767)};
    SDL_LockJoysticks();
    for (int button = 0; button <= SDL_GAMEPAD_BUTTON_DPAD_RIGHT; ++button)
        SDL_SetJoystickVirtualButton(orbis_joystick, button, (buttons >> button) & 1u);
    for (int axis = 0; axis < SDL_GAMEPAD_AXIS_COUNT; ++axis)
        SDL_SetJoystickVirtualAxis(orbis_joystick, axis, axes[axis]);
    SDL_UnlockJoysticks();
    return true;
}

/* The joystick ID SDL gave the console's controller, 0 while none is attached (for diagnostics). */
SDL_DECLSPEC Uint32 SDLCALL orbis_sdl_gamepad_id(void)
{
    return orbis_joystick ? (Uint32)orbis_id : 0;
}
