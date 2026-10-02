package orbis.smoke;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import org.lwjgl.Version;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL32C.*;

/**
 * Runner-launched LWJGL 3.2.2 smoke test for display=gpu. Every stage logs one line,
 * "SMOKE stage=<name> result=<ok|fail|skip> ...", to standard output (the runner's output
 * log). Stages: versions, init, window, context, capabilities, shader, pixels, frames,
 * input, teardown. Cleanup runs in reverse acquisition order after any failure.
 *
 * Properties: orbis.smoke.frames (default 600), orbis.smoke.fail=<stage> to inject a
 * failure right after that stage succeeds.
 */
public final class LwjglSmoke {
    private static final float[] CLEAR = {0.10f, 0.10f, 0.30f};
    private static final float[] TRIANGLE = {0.20f, 0.80f, 0.40f};
    private static final float[] PRESSED = {0.90f, 0.60f, 0.10f};
    private static final String VERTEX = "#version 150 core\n"
            + "in vec2 position;\nuniform vec2 offset;\n"
            + "void main() { gl_Position = vec4(position + offset, 0.0, 1.0); }\n";
    private static final String FRAGMENT = "#version 150 core\n"
            + "uniform vec3 color;\nout vec4 fragment;\n"
            + "void main() { fragment = vec4(color, 1.0); }\n";

    private static final Deque<Runnable> cleanup = new ArrayDeque<>();
    private static final String failAfter = System.getProperty("orbis.smoke.fail", "");
    private static int keyEvents, charEvents, buttonEvents, cursorEvents, scrollEvents;
    private static boolean keyHeld;
    private static double cursorX, cursorY;
    private static boolean escape;

    private LwjglSmoke() {}

    public static void main(String[] args) {
        boolean success = false;
        try {
            run(Integer.getInteger("orbis.smoke.frames", 600));
            success = true;
        } catch (Throwable failure) {
            log("run", "fail", failure.toString());
            failure.printStackTrace(System.out);
        } finally {
            int failures = 0;
            while (!cleanup.isEmpty()) {
                try {
                    cleanup.pop().run();
                } catch (Throwable failure) {
                    ++failures;
                    log("teardown", "fail", failure.toString());
                }
            }
            log("teardown", failures == 0 ? "ok" : "fail", "released all acquired resources");
            log("summary", success && failures == 0 ? "ok" : "fail", "");
        }
        if (!success) throw new IllegalStateException("LWJGL smoke test failed; see SMOKE lines above");
    }

    private static void run(int frames) {
        log("versions", "ok", "lwjgl=" + Version.getVersion() + " glfw=" + glfwGetVersionString()
                + " java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name"));
        glfwSetErrorCallback((code, description) ->
                log("glfw-error", "fail", "0x" + Integer.toHexString(code) + " " + MemoryUtil.memUTF8(description)));
        cleanup.push(() -> glfwSetErrorCallback(null));
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        cleanup.push(() -> { glfwTerminate(); log("teardown", "ok", "glfwTerminate"); });
        stage("init", "monitor=" + describe(glfwGetVideoMode(glfwGetPrimaryMonitor())));

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long window = glfwCreateWindow(1280, 720, "Orbis LWJGL smoke", 0, 0);
        if (window == 0) throw new IllegalStateException("glfwCreateWindow failed");
        cleanup.push(() -> { glfwDestroyWindow(window); log("teardown", "ok", "glfwDestroyWindow"); });
        int[] width = new int[1], height = new int[1];
        glfwGetFramebufferSize(window, width, height);
        stage("window", "framebuffer=" + width[0] + "x" + height[0]);

        glfwMakeContextCurrent(window);
        cleanup.push(() -> glfwMakeContextCurrent(0));
        if (glfwGetCurrentContext() != window) throw new IllegalStateException("context not current");
        glfwSwapInterval(1);
        stage("context", "current");

        GLCapabilities caps = GL.createCapabilities();
        cleanup.push(() -> GL.setCapabilities(null));
        stage("capabilities", "GL=" + glGetString(GL_VERSION) + " vendor=" + glGetString(GL_VENDOR)
                + " renderer=" + glGetString(GL_RENDERER) + " GLSL=" + glGetString(GL_SHADING_LANGUAGE_VERSION)
                + " core32=" + caps.OpenGL32);
        if (!caps.OpenGL32) throw new IllegalStateException("OpenGL 3.2 not available");

        int program = program();
        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        cleanup.push(() -> { glFinish(); glDeleteBuffers(vbo); glDeleteVertexArrays(vao); glDeleteProgram(program);
            log("teardown", "ok", "GL objects deleted after glFinish"); });
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertices = MemoryUtil.memAllocFloat(6).put(new float[]{-0.3f, -0.3f, 0.3f, -0.3f, 0f, 0.3f}).flip();
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        MemoryUtil.memFree(vertices);
        int position = glGetAttribLocation(program, "position");
        glEnableVertexAttribArray(position);
        glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, 0);
        glUseProgram(program);
        int offset = glGetUniformLocation(program, "offset");
        int color = glGetUniformLocation(program, "color");
        checkError("shader setup");
        stage("shader", "program=" + program + " position=" + position);

        glViewport(0, 0, width[0], height[0]);
        draw(offset, color, 0f, 0f, TRIANGLE);
        pixels(width[0], height[0]);
        glfwSwapBuffers(window);

        installInput(window);
        double start = glfwGetTime();
        double slowest = 0;
        int presented = 1;
        boolean captured = false, released = false;
        while (presented < frames && !glfwWindowShouldClose(window) && !escape) {
            double frameStart = glfwGetTime();
            glfwPollEvents();
            double elapsed = frameStart - start;
            if (!captured && elapsed >= 2.0) {
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                captured = true;
                log("input", "ok", "cursor captured at frame " + presented);
            } else if (captured && !released && elapsed >= 4.0) {
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                released = true;
                log("input", "ok", "cursor released at frame " + presented);
            }
            float x = (float) (cursorX / width[0] * 2 - 1), y = (float) (1 - cursorY / height[0] * 2);
            draw(offset, color, clamp(x), clamp(y), keyHeld ? PRESSED : TRIANGLE);
            checkError("frame " + presented);
            glfwSwapBuffers(window);
            ++presented;
            slowest = Math.max(slowest, glfwGetTime() - frameStart);
        }
        double seconds = glfwGetTime() - start;
        stage("frames", "presented=" + presented + " seconds=" + format(seconds) + " average_ms="
                + format(seconds * 1000 / presented) + " slowest_ms=" + format(slowest * 1000)
                + " ended_by=" + (escape ? "escape" : glfwWindowShouldClose(window) ? "close" : "count"));
        log("input", keyEvents + buttonEvents + cursorEvents > 0 ? "ok" : "skip",
                "keys=" + keyEvents + " chars=" + charEvents + " buttons=" + buttonEvents + " cursor=" + cursorEvents
                + " scroll=" + scrollEvents + " capture_toggled=" + (captured && released));
    }

    private static void installInput(long window) {
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            ++keyEvents;
            if (action != GLFW_REPEAT) keyHeld = action == GLFW_PRESS;
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) escape = true;
            if (keyEvents <= 8) log("input-event", "ok", "key=" + key + " scancode=" + scancode
                    + " action=" + action + " mods=" + mods);
        });
        glfwSetCharCallback(window, (w, codepoint) -> ++charEvents);
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            ++buttonEvents;
            if (buttonEvents <= 4) log("input-event", "ok", "button=" + button + " action=" + action);
        });
        glfwSetCursorPosCallback(window, (w, x, y) -> { ++cursorEvents; cursorX = x; cursorY = y; });
        glfwSetScrollCallback(window, (w, x, y) -> ++scrollEvents);
        int[] width = new int[1], height = new int[1];
        glfwGetFramebufferSize(window, width, height);
        cursorX = width[0] / 2.0;
        cursorY = height[0] / 2.0;
    }

    private static int program() {
        int vertex = shader(GL_VERTEX_SHADER, VERTEX), fragment = shader(GL_FRAGMENT_SHADER, FRAGMENT);
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glBindFragDataLocation(program, 0, "fragment");
        glLinkProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            String info = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException("program link failed: " + info);
        }
        return program;
    }

    private static int shader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
            String info = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("shader compile failed: " + info);
        }
        return shader;
    }

    private static void draw(int offset, int color, float x, float y, float[] rgb) {
        glClearColor(CLEAR[0], CLEAR[1], CLEAR[2], 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glUniform2f(offset, x, y);
        glUniform3f(color, rgb[0], rgb[1], rgb[2]);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    /** First frame: corner shows the clear colour, centre shows the triangle. */
    private static void pixels(int width, int height) {
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        try {
            glFinish();
            String corner = expectPixel(pixel, 2, 2, CLEAR);
            String centre = expectPixel(pixel, width / 2, height / 2, TRIANGLE);
            checkError("glReadPixels");
            stage("pixels", "corner=" + corner + " centre=" + centre);
        } finally {
            MemoryUtil.memFree(pixel);
        }
    }

    private static String expectPixel(ByteBuffer pixel, int x, int y, float[] rgb) {
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        int r = pixel.get(0) & 0xff, g = pixel.get(1) & 0xff, b = pixel.get(2) & 0xff;
        String actual = r + "," + g + "," + b;
        for (int i = 0; i < 3; i++) {
            int expected = Math.round(rgb[i] * 255), value = pixel.get(i) & 0xff;
            if (Math.abs(expected - value) > 2)
                throw new IllegalStateException("pixel " + x + "," + y + " is " + actual + ", expected "
                        + Math.round(rgb[0] * 255) + "," + Math.round(rgb[1] * 255) + "," + Math.round(rgb[2] * 255));
        }
        return actual;
    }

    private static void checkError(String where) {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException("GL error 0x" + Integer.toHexString(error) + " at " + where);
    }

    /** Logs a successful stage, then fails here if this stage was chosen for injection. */
    private static void stage(String name, String detail) {
        log(name, "ok", detail);
        if (name.equals(failAfter)) throw new IllegalStateException("injected failure after stage " + name);
    }

    private static void log(String stage, String result, String detail) {
        System.out.println("SMOKE stage=" + stage + " result=" + result + (detail.isEmpty() ? "" : " " + detail));
        System.out.flush();
    }

    private static String describe(GLFWVidMode mode) {
        return mode == null ? "none" : mode.width() + "x" + mode.height() + "@" + mode.refreshRate();
    }

    private static float clamp(float value) {
        return Math.max(-0.7f, Math.min(0.7f, value));
    }

    private static String format(double value) {
        return String.format("%.2f", value);
    }
}
