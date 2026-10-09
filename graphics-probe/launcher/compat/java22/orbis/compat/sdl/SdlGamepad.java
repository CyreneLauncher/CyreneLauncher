package orbis.compat.sdl;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * The console's controller into the console's libSDL3 (orbis_sdl_gamepad_update, built with SDL in
 * graphics-probe/lwjgl/sdl), which presents it to Controlify as a virtual PS4 gamepad. The state comes
 * from the GLFW facade (orbis.graphics.Gamepad). Java 22+: the foreign function API.
 */
public final class SdlGamepad {
    // PS4 pad button bits (the GLFW facade's raw state) in SDL_GamepadButton order: south, east, west,
    // north, back (touchpad click), guide (the PS button stays with the system), start (options),
    // left stick, right stick, left shoulder, right shoulder, dpad up, down, left, right.
    private static final int[] BUTTONS = {0x4000, 0x2000, 0x8000, 0x1000, 0x100000, 0, 0x0008,
        0x0002, 0x0004, 0x0400, 0x0800, 0x0010, 0x0040, 0x0080, 0x0020};

    private static MethodHandle update, error, attachedId;
    private static String lastReport = "";
    private static Method read;
    private static int retry;
    /** Reads in a row without a controller; the gamepad is detached only after DETACH_AFTER of them. */
    private static int missing;
    private static final int DETACH_AFTER = 60;
    private static final int[] state = new int[8];

    private SdlGamepad() {}

    /** Called before Controlify polls SDL. Until Controlify has loaded libSDL3, looks again now and then. */
    @SuppressWarnings("restricted")   // native access is the point; the runtime allows it for the game
    public static void tick(ClassLoader game) throws Throwable {
        if (update == null) {
            if (retry-- > 0) return;
            retry = 100;
            // libSDL3 is loaded by Controlify through System.loadLibrary in the game's loader, as this class is.
            Optional<MemorySegment> symbol = SymbolLookup.loaderLookup().find("orbis_sdl_gamepad_update");
            if (symbol.isEmpty()) return;
            read = Class.forName("orbis.graphics.Gamepad", true, game).getMethod("read", int[].class);
            update = Linker.nativeLinker().downcallHandle(symbol.get(), FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN,
                    ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT, ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT,
                    ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT));
            SymbolLookup sdl = SymbolLookup.loaderLookup();
            error = Linker.nativeLinker().downcallHandle(sdl.find("SDL_GetError").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            attachedId = Linker.nativeLinker().downcallHandle(sdl.find("orbis_sdl_gamepad_id").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            System.out.println("orbis-compat: the controller feeds SDL for Controlify");
        }
        boolean connected = (Boolean) read.invoke(null, (Object) state);
        // A controller that drops out for a moment stays attached, at rest, so Controlify does not see
        // it unplugged and plugged in again.
        if (connected) {
            missing = 0;
        } else if (++missing < DETACH_AFTER) {
            connected = true;
            state[1] = 0;
            state[2] = state[3] = state[4] = state[5] = 128;
            state[6] = state[7] = 0;
        }
        int buttons = 0;
        if (connected)
            for (int i = 0; i < BUTTONS.length; i++)
                if (BUTTONS[i] != 0 && (state[1] & BUTTONS[i]) != 0) buttons |= 1 << i;
        boolean fed = (boolean) update.invokeExact(connected, buttons, stick(state[2], connected), stick(state[3], connected),
                stick(state[4], connected), stick(state[5], connected), trigger(state[6], connected), trigger(state[7], connected));
        // One line whenever the controller's state towards SDL changes.
        int id = (int) attachedId.invokeExact();
        String report = !connected ? "no controller" : fed ? "attached as SDL joystick " + id : "not attached: " + sdlError();
        if (!report.equals(lastReport)) {
            lastReport = report;
            System.out.println("orbis-compat: SDL controller feed: " + report);
        }
    }

    @SuppressWarnings("restricted")   // SDL_GetError returns a C string of unknown length
    private static String sdlError() throws Throwable {
        MemorySegment message = (MemorySegment) error.invokeExact();
        return message.equals(MemorySegment.NULL) ? "" : message.reinterpret(1024).getString(0);
    }

    /** 0..255 with 128 at rest, as SDL's -32768..32767. */
    static short stick(int raw, boolean connected) {
        if (!connected) return 0;
        return (short) Math.max(-32768, Math.min(32767, (raw - 128) * 257));
    }

    /** 0..255 as SDL's 0..32767. */
    static short trigger(int raw, boolean connected) {
        if (!connected) return 0;
        return (short) Math.min(32767, Math.max(0, raw) * 32767 / 255);
    }
}
