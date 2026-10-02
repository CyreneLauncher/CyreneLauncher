package runner.desktop;

import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;
import com.github.caciocavallosilano.cacio.ctc.CTCEventSource;

/** One process-wide desktop runtime. Application/AWT non-daemon threads own lifetime. */
public final class DesktopRuntime {
    private static final Object INITIALIZATION = new Object();
    private static final Object LIFECYCLE = new Object();
    private static final long FRAME_PERIOD = 33_333_334L; // At most 30 Hz.
    private static volatile DesktopSession session;
    private static volatile DesktopInputAdapter input;
    private static volatile Thread frameWorker;
    private static volatile boolean stopping;
    private static boolean started;
    private static volatile boolean bridgeAvailable;
    private static Throwable initializationFailure;

    private DesktopRuntime() { }

    public static void initialize() {
        synchronized (INITIALIZATION) {
            if (stopping) throw new IllegalStateException("Desktop runtime stopped; restart unsupported");
            if (session != null) return;
            if (initializationFailure != null) throw new IllegalStateException("Desktop initialization failed", initializationFailure);
            try {
                // No toolkit/graphics initialization until artifacts and native ABI pass.
                DesktopArtifacts.validate(Path.of(System.getProperty("java.home")),
                        Path.of(System.getProperty("runner.desktop.bundle")));
                int[] caps = new int[NativeDesktopBridge.CAPABILITY_WORDS];
                int queryStatus = NativeDesktopBridge.query(caps);
                bridgeAvailable = true;
                if (queryStatus != 0 || caps[0] != 1 || caps[1] != 1280
                        || caps[2] != 720 || caps[3] != 1280 || caps[4] != 1 || caps[5] != 24) {
                    throw new IllegalStateException("Native desktop capability contract mismatch");
                }
                DesktopInputAdapter source = new DesktopInputAdapter();
                input = source;
                CTCEventSource.installDesktopSource(source);
                Runtime.getRuntime().addShutdownHook(new Thread(DesktopRuntime::shutdown, "DesktopShutdown"));
                DesktopSession created = DesktopBootstrap.initialize();
                if (stopping) throw new IllegalStateException("Desktop stopped during initialization");
                session = created;
            } catch (Throwable failure) {
                initializationFailure = failure;
                shutdown();
                throw new IllegalStateException("Desktop runtime initialization failed", failure);
            }
        }
    }

    public static ClassLoader createApplicationLoader(URL[] urls) {
        if (session == null || stopping) throw new IllegalStateException("Desktop runtime unavailable");
        return DesktopBootstrap.createApplicationLoader(urls);
    }

    public static void start() {
        initialize();
        synchronized (LIFECYCLE) {
            if (stopping) throw new IllegalStateException("Desktop runtime stopped; restart unsupported");
            if (started) return;
            try {
                int status = NativeDesktopBridge.start();
                if (status != 0) throw new IllegalStateException("Native desktop start failed: " + status);
                // Allocate once before publication and retain only this one frame.
                int[] frame = new int[NativeDesktopBridge.PIXEL_COUNT];
                Thread worker = new Thread(() -> publishFrames(frame), "DesktopFrames");
                worker.setDaemon(true);
                frameWorker = worker;
                input.enable();
                worker.start();
                started = true;
            } catch (Throwable failure) {
                stopping = true;
                input.requestStop();
                NativeDesktopBridge.stop();
                throw new IllegalStateException("Desktop start failed", failure);
            }
        }
    }

    private static void publishFrames(int[] frame) {
        int failures = 0;
        boolean failureReported = false;
        long deadline = System.nanoTime();
        while (!stopping && !Thread.currentThread().isInterrupted()) {
            for (;;) {
                if (stopping || Thread.currentThread().isInterrupted()) return;
                long delay = deadline - System.nanoTime();
                if (delay <= 0) break;
                // parkNanos may return spuriously; recheck deadline after every wake.
                LockSupport.parkNanos(delay);
            }
            if (stopping || Thread.currentThread().isInterrupted()) return;
            try {
                long sequence = session.copyFrame(frame);
                if (stopping || Thread.currentThread().isInterrupted()) return;
                if (NativeDesktopBridge.submit(frame, sequence) != 0) {
                    throw new IllegalStateException("Native frame submission rejected");
                }
                failures = 0;
            } catch (RuntimeException | LinkageError failure) {
                if (stopping || Thread.currentThread().isInterrupted()) return;
                if (!failureReported) {
                    failureReported = true;
                    System.err.println("Desktop frame publication failed; retaining prior frame: "
                            + failure.getClass().getSimpleName());
                }
                if (++failures == 5) {
                    System.err.println("Desktop publication stopped after five consecutive frame failures: "
                            + failure.getClass().getSimpleName());
                    shutdown();
                    return;
                }
            }
            // Schedule from completion if work missed its deadline: no catch-up bursts.
            deadline = Math.max(deadline + FRAME_PERIOD, System.nanoTime() + FRAME_PERIOD);
        }
    }

    static void inputFailed(int status) {
        if (!stopping) {
            System.err.println("Desktop input stopped: " + status);
            shutdown();
        }
    }

    /** Finite joins; native services/storage remain launcher owned. */
    public static void shutdown() {
        synchronized (LIFECYCLE) {
            if (!stopping) stopping = true;
            DesktopInputAdapter source = input;
            if (source != null) source.requestStop();
            Thread frames = frameWorker;
            if (frames != null) frames.interrupt();
            // stop synchronizes publication and returns focus to the console.
            if (bridgeAvailable) NativeDesktopBridge.stop();
        }
        joinBounded(frameWorker);
        DesktopInputAdapter source = input;
        if (source != null) joinBounded(source.consumerThread());
        // Do not take CTCScreen's drawing lock here. A snapshot or application
        // painting callback can remain blocked; stopping prevents later submit.
        // AWT and the application loader intentionally remain alive until JVM exit.
    }

    private static void joinBounded(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(500); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
