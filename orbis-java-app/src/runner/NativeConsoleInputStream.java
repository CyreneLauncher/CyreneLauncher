package runner;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Reads command input from the Orbis keyboard through the screen-console bridge. */
final class NativeConsoleInputStream extends InputStream {
    private static native int readByte();

    /** Return promptly after one keystroke instead of filling the caller's buffer. */
    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        bytes[offset] = (byte) read();
        return 1;
    }

    @Override
    public int read() throws IOException {
        int value = readByte();
        if (value < 0) throw new IOException("Orbis keyboard input unavailable");
        return value;
    }
}
