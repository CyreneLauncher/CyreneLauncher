package runner;

import java.io.IOException;
import java.io.OutputStream;

/** Sends Java text to the native on-screen text console. */
final class NativeConsoleOutputStream extends OutputStream {
    private static native void screenWrite(byte[] bytes, int offset, int length);
    private static native void screenFlush();

    @Override
    public void write(int value) {
        byte[] one = {(byte) value};
        screenWrite(one, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || offset + length > bytes.length)
            throw new IndexOutOfBoundsException();
        if (length != 0) screenWrite(bytes, offset, length);
    }

    @Override
    public void flush() {
        screenFlush();
    }
}
