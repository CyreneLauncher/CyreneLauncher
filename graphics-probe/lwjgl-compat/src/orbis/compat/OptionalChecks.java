package orbis.compat;

import java.nio.ShortBuffer;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.system.MemoryUtil;
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
    /** Returns what was verified: playback through a real device, or the no-device contract. */
    public static String checkAudio() {
        // Minecraft relies on ALC's implicit creation when the class initialises (no explicitInit),
        // so check that state first, then repeated explicit create/destroy.
        try {
            ALC.getFunctionProvider();
        } catch (IllegalStateException notLoaded) { // explicitInit, or destroyed by an earlier check
            ALC.create();
        }
        String result;
        try { result = checkDevice(); } finally { ALC.destroy(); }
        for (int i = 0; i < 3; i++) {
            ALC.create();
            try { checkDevice(); } finally { ALC.destroy(); }
        }
        return result;
    }
    private static String checkDevice() {
        long device = ALC10.alcOpenDevice((java.nio.ByteBuffer)null);
        if (device == 0) {
            require(ALC10.alcGetError(0) == ALC10.ALC_INVALID_DEVICE, "open error");
            require(ALC10.alcGetError(0) == ALC10.ALC_NO_ERROR, "error not cleared");
            require(!ALC10.alcIsExtensionPresent(0, "ALC_ENUMERATE_ALL_EXT"), "enumeration advertised");
            require(ALC10.alcGetCurrentContext() == 0, "context fabricated");
            return "no device: official ALC bootstrap, one-shot error, no context";
        }
        String name = ALC10.alcGetString(device, ALC10.ALC_DEVICE_SPECIFIER);
        long context = ALC10.alcCreateContext(device, (java.nio.IntBuffer)null);
        require(context != 0, "context creation failed on " + name);
        int source = 0, buffer = 0;
        ShortBuffer tone = MemoryUtil.memAllocShort(24000);
        try {
            require(ALC10.alcMakeContextCurrent(context), "context not current");
            AL.createCapabilities(org.lwjgl.openal.ALC.createCapabilities(device));
            for (int i = 0; i < tone.capacity(); i++) // 0.5 s, 440 Hz, quiet
                tone.put(i, (short) (Math.sin(2 * Math.PI * 440 * i / 48000.0) * 6000));
            buffer = AL10.alGenBuffers();
            AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, tone, 48000);
            source = AL10.alGenSources();
            AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
            AL10.alSourcePlay(source);
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING && System.nanoTime() < deadline)
                Thread.sleep(20);
            int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
            require(state == AL10.AL_STOPPED, "tone did not finish playing (state 0x" + Integer.toHexString(state) + ")");
            require(AL10.alGetError() == AL10.AL_NO_ERROR, "AL error during playback");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted during playback");
        } finally {
            if (source != 0) AL10.alDeleteSources(source);
            if (buffer != 0) AL10.alDeleteBuffers(buffer);
            MemoryUtil.memFree(tone);
            ALC10.alcMakeContextCurrent(0);
            AL.setCurrentProcess(null);
            ALC10.alcDestroyContext(context);
            ALC10.alcCloseDevice(device);
        }
        return "device '" + name + "': 0.5 s 440 Hz tone played to completion";
    }
    public static void checkDialogs() {
        require(!TinyFileDialogs.tinyfd_messageBox("Unavailable", "Cancel expected", "yesno", "info", false), "message accepted");
        require(TinyFileDialogs.tinyfd_openFileDialog("Unavailable", null, null, null, false) == null, "file selected");
    }
}
