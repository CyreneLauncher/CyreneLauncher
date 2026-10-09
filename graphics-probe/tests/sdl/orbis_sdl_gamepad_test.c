/* Host test of lwjgl/sdl/orbis_sdl_gamepad.c against an official libSDL3 of the pinned release. */
#include <SDL3/SDL.h>
#include <stdio.h>
#include <stdlib.h>

bool orbis_sdl_gamepad_update(bool connected, Uint32 buttons, Sint16 left_x, Sint16 left_y, Sint16 right_x,
                              Sint16 right_y, Sint16 left_trigger, Sint16 right_trigger);

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "check failed line %d: %s\n", __LINE__, #c); exit(1); } } while (0)

int main(void)
{
    CHECK(!orbis_sdl_gamepad_update(true, 0, 0, 0, 0, 0, 0, 0));     /* before SDL's joystick subsystem: nothing */
    CHECK(SDL_Init(SDL_INIT_GAMEPAD));
    /* The pad starts neutral and Controlify opens the gamepad when it connects: SDL takes an axis's first
     * value as its resting position, and a trigger held before the gamepad opened reads 0 until it moves. */
    CHECK(orbis_sdl_gamepad_update(true, 0, 0, 0, 0, 0, 0, 0));
    SDL_UpdateGamepads();
    int count = 0;
    SDL_JoystickID *pads = SDL_GetGamepads(&count);
    CHECK(count == 1);
    SDL_Gamepad *pad = SDL_OpenGamepad(pads[0]);
    SDL_free(pads);
    CHECK(pad != NULL);
    Uint32 buttons = (1u << SDL_GAMEPAD_BUTTON_SOUTH) | (1u << SDL_GAMEPAD_BUTTON_DPAD_UP);
    CHECK(orbis_sdl_gamepad_update(true, buttons, 32767, -32768, 0, 100, 32767, 0));
    SDL_UpdateGamepads();
    CHECK(SDL_GetGamepadType(pad) == SDL_GAMEPAD_TYPE_PS4);
    CHECK(SDL_GetGamepadVendor(pad) == 0x054C && SDL_GetGamepadProduct(pad) == 0x09CC);
    CHECK(SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_SOUTH) && SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_DPAD_UP));
    CHECK(!SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_EAST));
    CHECK(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_LEFTX) == 32767 && SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_LEFTY) == -32768);
    CHECK(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_RIGHTY) == 100 && SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_LEFT_TRIGGER) == 32767);
    CHECK(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER) == 0);
    CHECK(orbis_sdl_gamepad_update(true, 1u << SDL_GAMEPAD_BUTTON_EAST, 0, 0, 0, 0, 0, 0));
    SDL_UpdateGamepads();
    CHECK(SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_EAST) && !SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_SOUTH));
    SDL_CloseGamepad(pad);
    CHECK(orbis_sdl_gamepad_update(false, 0, 0, 0, 0, 0, 0, 0));
    SDL_UpdateGamepads();
    pads = SDL_GetGamepads(&count);
    SDL_free(pads);
    CHECK(count == 0);
    /* SDL shut down and started again: a new gamepad is attached, not the freed one. */
    SDL_Quit();
    CHECK(SDL_Init(SDL_INIT_GAMEPAD));
    CHECK(orbis_sdl_gamepad_update(true, 0, 0, 0, 0, 0, 0, 0));
    pads = SDL_GetGamepads(&count);
    SDL_free(pads);
    CHECK(count == 1);
    SDL_Quit();
    puts("SDL virtual gamepad host behavior passed");
    return 0;
}
