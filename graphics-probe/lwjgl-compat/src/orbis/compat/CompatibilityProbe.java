package orbis.compat;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLDebugMessageARBCallback;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.KHRDebug;
import org.lwjgl.stb.STBImage;
import org.lwjgl.stb.STBIEOFCallback;
import org.lwjgl.stb.STBIIOCallbacks;
import org.lwjgl.stb.STBIReadCallback;
import org.lwjgl.stb.STBISkipCallback;
import org.lwjgl.system.Library;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;

/** Runner-launched official LWJGL 3.2.2 compatibility and cleanup gate. */
public final class CompatibilityProbe {
    private static final Deque<NamedCleanup> cleanup = new ArrayDeque<>();
    private static final String failAfter = System.getProperty("orbis.compat.fail", "");
    private static boolean success;
    private static boolean expectingGlfwError;
    private static final AtomicInteger unexpectedGlfwErrors = new AtomicInteger();

    private CompatibilityProbe() {}

    private record NamedCleanup(String name, Runnable action) {}

    public static void main(String[] args) throws Exception {
        Path fixtures = args.length == 0 ? Path.of("fixtures") : Path.of(args[0]);
        boolean cleaned = true;
        try {
            run(fixtures);
            success = true;
        } catch (Throwable failure) {
            log("run", "fail", failure.toString());
            failure.printStackTrace(System.out);
        } finally {
            while (!cleanup.isEmpty()) {
                NamedCleanup item = cleanup.pop();
                try {
                    item.action().run();
                    log("teardown", "ok", item.name());
                } catch (Throwable failure) {
                    cleaned = false;
                    log("teardown", "fail", item.name() + " " + failure);
                }
            }
            log("teardown", cleaned ? "ok" : "fail", "released all acquired resources");
            log("summary", success && cleaned && unexpectedGlfwErrors.get() == 0 ? "ok" : "fail", "");
        }
        if (!success || !cleaned || unexpectedGlfwErrors.get() != 0)
            throw new IllegalStateException("compatibility probe failed; inspect COMPAT lines");
    }

    /** Minecraft creates a read callback per texture on several threads; keep thousands alive at once. */
    private static String stressCallbacks() throws InterruptedException {
        final int live = 2000, rounds = 5, threads = 4;
        AtomicInteger created = new AtomicInteger();
        Throwable[] failure = new Throwable[1];
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                try {
                    for (int r = 0; r < rounds; r++) {
                        STBIReadCallback[] held = new STBIReadCallback[live / threads];
                        for (int i = 0; i < held.length; i++) {
                            held[i] = STBIReadCallback.create((user, data, size) -> 0);
                            created.incrementAndGet();
                        }
                        for (STBIReadCallback callback : held) callback.free();
                    }
                } catch (Throwable e) {
                    synchronized (failure) { if (failure[0] == null) failure[0] = e; }
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) worker.join();
        if (failure[0] != null)
            throw new IllegalStateException("callback creation failed after " + created.get() + " callbacks", failure[0]);
        return created.get() + " callbacks on " + threads + " threads, " + live + " alive at once, all freed";
    }

    private static void run(Path fixtures) throws Exception {
        Library.initialize(); // Actually loads liblwjgl, including JNI and dyncall.
        ByteBuffer allocation = MemoryUtil.memAlloc(16);
        cleanup.push(new NamedCleanup("native allocation", () -> MemoryUtil.memFree(allocation)));
        stage("native-load", "LWJGL core JNI and system allocator available");

        StbChecks.checkImage(fixtures);
        stage("stb-image", "PNG pixels, resize, malformed input, allocation/free");
        StbChecks.checkFont(fixtures);
        stage("stb-font", "TrueType metrics and raster/free");
        StbChecks.checkVorbis(fixtures);
        stage("stb-vorbis", "PCM decode and malformed input");
        int[] callbackCounts;
        try {
            StbChecks.checkCallbacks(fixtures);
            callbackCounts = readCallbackRoundtrip(fixtures.resolve("pixels.png"));
        } catch (Throwable failure) {
            throw new IllegalStateException("STB dyncall callback trampoline creation/invocation failed; "
                    + "check executable memory mapping and callback ABI", failure);
        }
        stage("stb-callback", "PNG write/read dyncall roundtrip read=" + callbackCounts[0]
                + " skip=" + callbackCounts[1] + " eof=" + callbackCounts[2] + " callbacks freed");
        StbChecks.run(fixtures); // Aggregate fixture contract remains reusable for host and device.
        stage("callback-pool", stressCallbacks());

        stage("alc", OptionalChecks.checkAudio());
        OptionalChecks.checkDialogs();
        stage("tinyfd-cancel", "official TinyFD wrappers return cancellation");
        OptionalChecks.run(); // Also checks repeated bootstrap/teardown as a combined contract.

        glfwSetErrorCallback((code, description) -> {
            boolean expected = expectingGlfwError;
            if (!expected) unexpectedGlfwErrors.incrementAndGet();
            log(expected ? "glfw-expected-error" : "glfw-error", expected ? "ok" : "fail",
                    "0x" + Integer.toHexString(code) + " " + MemoryUtil.memUTF8(description));
        });
        cleanup.push(new NamedCleanup("glfwSetErrorCallback(null)", () -> glfwSetErrorCallback(null)));
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        cleanup.push(new NamedCleanup("glfwTerminate", () -> glfwTerminate()));
        stage("glfw-init", "EGL provider and input bridge initialized");
        long monitor = glfwGetPrimaryMonitor();
        require(monitor != 0, "no primary monitor");
        int beforeWidth = glfwGetVideoMode(monitor).width();
        int beforeHeight = glfwGetVideoMode(monitor).height();
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
        glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_NATIVE_CONTEXT_API);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        require(glfwGetError(null) == GLFW_NO_ERROR, "Minecraft context hints rejected");
        long window = glfwCreateWindow(854, 480, "Orbis compatibility probe", 0, 0);
        require(window != 0, "glfwCreateWindow failed");
        cleanup.push(new NamedCleanup("glfwDestroyWindow", () -> glfwDestroyWindow(window)));
        require(unexpectedGlfwErrors.get() == 0, "unexpected GLFW error callback during window/input acquisition");
        stage("glfw-window", "one fullscreen scanout surface acquired");
        int afterWidth = glfwGetVideoMode(monitor).width();
        int afterHeight = glfwGetVideoMode(monitor).height();
        int[] width = new int[1], height = new int[1];
        glfwGetFramebufferSize(window, width, height);
        require(beforeWidth > 0 && beforeHeight > 0 && afterWidth == width[0] && afterHeight == height[0],
                "monitor/framebuffer dimensions disagree");
        glfwSetWindowMonitor(window, monitor, 0, 0, afterWidth, afterHeight, 60);
        require(glfwGetWindowMonitor(window) == monitor, "fullscreen transition failed");
        glfwSetWindowMonitor(window, 0, 0, 0, 854, 480, 60);
        require(glfwGetWindowMonitor(window) == 0, "windowed flag transition failed");
        glfwSetWindowIcon(window, null);
        glfwSetClipboardString(window, "compat-clipboard");
        require("compat-clipboard".equals(glfwGetClipboardString(window)), "clipboard roundtrip failed");
        expectingGlfwError = true;
        try {
            glfwWindowHint(0x7ffffffe, 1);
            require(glfwGetError(null) == GLFW_INVALID_ENUM, "unknown rendering hint accepted");
            glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_EGL_CONTEXT_API);
            require(glfwGetError(null) == GLFW_INVALID_VALUE, "unsupported context creation API accepted");
        } finally { expectingGlfwError = false; }
        stage("glfw-policy", "monitor=" + beforeWidth + "x" + beforeHeight + "->" + afterWidth + "x" + afterHeight
                + " fixed scanout, transitions, icon, clipboard, hints");

        glfwMakeContextCurrent(window);
        require(glfwGetCurrentContext() == window, "context not current");
        cleanup.push(new NamedCleanup("glfwMakeContextCurrent(0)", () -> glfwMakeContextCurrent(0)));
        stage("gl-context", "current OpenGL context");
        GLCapabilities caps = GL.createCapabilities();
        cleanup.push(new NamedCleanup("GL.setCapabilities(null)", () -> GL.setCapabilities(null)));
        stage("gl-capabilities", "OpenGL=" + glGetString(GL_VERSION));
        debugCallback(caps);
    }

    private static void debugCallback(GLCapabilities caps) {
        if (!caps.GL_KHR_debug && !caps.GL_ARB_debug_output) {
            stage("gl-debug-callback", "not advertised by GL capabilities");
            return;
        }
        final int[] invoked = {0};
        if (caps.GL_KHR_debug) {
            GLDebugMessageCallback callback;
            try {
                callback = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
                    if (id == 0x4f52 && MemoryUtil.memUTF8(message, length).contains("compat")) invoked[0]++;
                });
            } catch (Throwable failure) {
                throw new IllegalStateException("GL KHR debug dyncall trampoline allocation failed", failure);
            }
            cleanup.push(new NamedCleanup("GLDebugMessageCallback.free", callback::free));
            KHRDebug.glDebugMessageCallback(callback, 0);
            cleanup.push(new NamedCleanup("glDebugMessageCallback(null)", () -> KHRDebug.glDebugMessageCallback(null, 0)));
            glEnable(KHRDebug.GL_DEBUG_OUTPUT);
            glEnable(KHRDebug.GL_DEBUG_OUTPUT_SYNCHRONOUS);
            KHRDebug.glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, GL_DONT_CARE, 0, true);
            KHRDebug.glDebugMessageInsert(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, KHRDebug.GL_DEBUG_TYPE_MARKER,
                    0x4f52, KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION, "compat-khr-callback");
        } else {
            GLDebugMessageARBCallback callback;
            try {
                callback = GLDebugMessageARBCallback.create((source, type, id, severity, length, message, user) -> {
                    if (id == 0x4f52 && MemoryUtil.memUTF8(message, length).contains("compat")) invoked[0]++;
                });
            } catch (Throwable failure) {
                throw new IllegalStateException("GL ARB debug dyncall trampoline allocation failed", failure);
            }
            cleanup.push(new NamedCleanup("GLDebugMessageARBCallback.free", callback::free));
            ARBDebugOutput.glDebugMessageCallbackARB(callback, 0);
            cleanup.push(new NamedCleanup("glDebugMessageCallbackARB(null)",
                    () -> ARBDebugOutput.glDebugMessageCallbackARB(null, 0)));
            glEnable(ARBDebugOutput.GL_DEBUG_OUTPUT_SYNCHRONOUS_ARB);
            ARBDebugOutput.glDebugMessageControlARB(GL_DONT_CARE, GL_DONT_CARE, GL_DONT_CARE, 0, true);
            ARBDebugOutput.glDebugMessageInsertARB(ARBDebugOutput.GL_DEBUG_SOURCE_APPLICATION_ARB,
                    ARBDebugOutput.GL_DEBUG_TYPE_OTHER_ARB, 0x4f52,
                    ARBDebugOutput.GL_DEBUG_SEVERITY_HIGH_ARB, "compat-arb-callback");
        }
        require(invoked[0] > 0, "advertised GL debug callback was not invoked synchronously");
        stage("gl-debug-callback", "advertised callback invoked through dyncall trampoline");
    }

    private static int[] readCallbackRoundtrip(Path png) throws Exception {
        byte[] bytes = Files.readAllBytes(png);
        int[] position = {0}, reads = {0}, skips = {0}, eofs = {0};
        try (STBIReadCallback read = STBIReadCallback.create((user, destination, size) -> {
                 reads[0]++;
                 int count = Math.min(size, bytes.length - position[0]);
                 for (int i = 0; i < count; i++) MemoryUtil.memPutByte(destination + i, bytes[position[0] + i]);
                 position[0] += count;
                 return count;
             });
             STBISkipCallback skip = STBISkipCallback.create((user, amount) -> {
                 skips[0]++;
                 position[0] = Math.max(0, Math.min(bytes.length, position[0] + amount));
             });
             STBIEOFCallback eof = STBIEOFCallback.create(user -> {
                 eofs[0]++;
                 return position[0] >= bytes.length ? 1 : 0;
             });
             STBIIOCallbacks callbacks = STBIIOCallbacks.calloc()) {
            callbacks.set(read, skip, eof);
            int[] width = new int[1], height = new int[1], channels = new int[1];
            ByteBuffer decoded = STBImage.stbi_load_from_callbacks(callbacks, 0, width, height, channels, 4);
            require(decoded != null, "STB read callback PNG decode: " + STBImage.stbi_failure_reason());
            try {
                require(width[0] == 2 && height[0] == 2 && (decoded.get(0) & 255) == 255,
                        "STB read callback pixels");
            } finally { STBImage.stbi_image_free(decoded); }
            // Tiny PNG fits STB's initial read buffer. Invoke the other two native
            // function pointers explicitly so every dyncall trampoline executes.
            JNI.invokePV(0L, 0, skip.address());
            require(JNI.invokePI(0L, eof.address()) == (position[0] >= bytes.length ? 1 : 0),
                    "STB EOF callback ABI");
        }
        require(reads[0] > 0 && skips[0] > 0 && eofs[0] > 0,
                "STB callback invocation counts read=" + reads[0] + " skip=" + skips[0] + " eof=" + eofs[0]);
        return new int[]{reads[0], skips[0], eofs[0]};
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private static void stage(String name, String detail) {
        log(name, "ok", detail);
        if (name.equals(failAfter)) throw new IllegalStateException("injected failure after " + name);
    }

    private static void log(String stage, String result, String detail) {
        System.out.println("COMPAT stage=" + stage + " result=" + result + (detail.isEmpty() ? "" : " " + detail));
        System.out.flush();
    }
}
