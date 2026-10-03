package orbis.compat;

import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Official LWJGL optional-service contract; each check can be stage-logged by the caller. */
public final class OptionalChecks {
    private OptionalChecks() {}
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void run() {
        checkAudio();
        checkDialogs();
    }
    public static void checkAudio() {
        // Minecraft relies on ALC's implicit creation when the class initialises (no explicitInit),
        // so check that state first, then repeated explicit create/destroy.
        try {
            ALC.getFunctionProvider();
        } catch (IllegalStateException notLoaded) { // explicitInit, or destroyed by an earlier check
            ALC.create();
        }
        try { checkNoDevice(); } finally { ALC.destroy(); }
        for (int i = 0; i < 3; i++) {
            ALC.create();
            try { checkNoDevice(); } finally { ALC.destroy(); }
        }
    }
    private static void checkNoDevice() {
        require(!ALC10.alcIsExtensionPresent(0, "ALC_ENUMERATE_ALL_EXT"), "enumeration advertised");
        require(ALC10.alcOpenDevice((java.nio.ByteBuffer)null) == 0, "audio device opened");
        require(ALC10.alcGetError(0) == ALC10.ALC_INVALID_DEVICE, "open error");
        require(ALC10.alcGetError(0) == ALC10.ALC_NO_ERROR, "error not cleared");
        require(ALC10.alcGetCurrentContext() == 0, "context fabricated");
    }
    public static void checkDialogs() {
        require(!TinyFileDialogs.tinyfd_messageBox("Unavailable", "Cancel expected", "yesno", "info", false), "message accepted");
        require(TinyFileDialogs.tinyfd_openFileDialog("Unavailable", null, null, null, false) == null, "file selected");
    }
}
