package runner.desktop;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import com.github.caciocavallosilano.cacio.ctc.CTCScreen;

/** Successfully initialized process-wide Cacio desktop session. */
public final class DesktopSession implements AutoCloseable {
    private final GraphicsEnvironment graphicsEnvironment;
    private final Toolkit toolkit;

    DesktopSession(GraphicsEnvironment graphicsEnvironment, Toolkit toolkit) {
        this.graphicsEnvironment = graphicsEnvironment;
        this.toolkit = toolkit;
    }

    public GraphicsEnvironment graphicsEnvironment() {
        return graphicsEnvironment;
    }

    public Toolkit toolkit() {
        return toolkit;
    }

    public int width() { return 1280; }
    public int height() { return 720; }
    public int stridePixels() { return 1280; }

    /** Copy one coherent screen image into caller-owned canonical ARGB storage. */
    public long copyFrame(int[] destination) {
        if (destination == null || destination.length != 1280 * 720) {
            throw new IllegalArgumentException("destination must contain exactly 921600 ARGB pixels");
        }
        return CTCScreen.copyCurrentScreenARGB(destination);
    }

    /** Closing only disables submission; it does not shut down AWT or app threads. */
    @Override
    public void close() {
        CTCScreen.closeFrameSubmission();
    }
}
