package orbis.launcher;

import orbis.graphics.NativePlatform;

/** System IME ownership on the launcher/render thread; failed teardown retains ownership. */
final class SystemKeyboard implements AutoCloseable {
    private boolean active, cancelling;

    boolean begin(String initial, String title, int maxLength) {
        if (active) throw new IllegalStateException("The previous text dialog is still closing");
        int error = NativePlatform.nImeBegin(initial, title, maxLength);
        if (error < 0) throw new IllegalStateException(NativePlatform.nLastError());
        active = true;
        cancelling = false;
        return true;
    }

    int poll() {
        if (!active) return 0;
        int status = NativePlatform.nImePoll();
        if (status != 1) active = false; // Native terminal status guarantees released ownership.
        if (status < 0) throw new IllegalStateException(NativePlatform.nLastError());
        return cancelling && status == 2 ? 3 : status;
    }

    String result() { return NativePlatform.nImeResult(); }
    boolean active() { return active; }
    boolean cancelling() { return cancelling; }

    /** Returns false while native Term needs another nonblocking poll. */
    boolean cancel() {
        if (!active) return true;
        cancelling = true;
        int status = NativePlatform.nImeCancel();
        if (status == 0) active = false;
        return !active;
    }

    @Override public void close() { cancel(); }
}
