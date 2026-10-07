package orbis.probe;

import java.nio.ByteBuffer;
import java.util.Locale;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

/**
 * Conditional rendering on the PS4 Pro. radeonsi predicates GL conditional rendering with SET_PREDICATION's ZPASS
 * operation for occlusion queries (si_query.c); RADV found that this command processor HANGS on the BOOL64
 * operation, and ZPASS had never been run on it. One occlusion query passes samples, one passes none; then draws
 * (and a clear) run under each, in every GL wait mode, inverted ones included, and the pixels are read back.
 * Each case logs before and after a glFinish, so a GPU hang names the case it stopped in. Lines start with CR.
 * Measured 2026-10-07: no hang, every waiting mode exact; NO_WAIT modes draw unconditionally, as the spec allows.
 */
public final class ConditionalProbe {
    private static final int SIZE = 256;
    private static final int RED = 0xff0000ff, GREEN = 0xff00ff00, BLACK = 0xff000000;
    private static int failures;
    private static int program;

    private static final String VS = """
            #version 430 core
            uniform vec4 rect;
            void main() {
                vec2 corner = vec2(gl_VertexID & 1, gl_VertexID >> 1);
                gl_Position = vec4(mix(rect.xy, rect.zw, corner), 0.0, 1.0);
            }
            """;
    private static final String FS = """
            #version 430 core
            uniform vec4 color;
            out vec4 result;
            void main() { result = color; }
            """;

    private ConditionalProbe() {}

    public static void main(String[] args) {
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        long window = 0;
        try {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(640, 480, "Orbis conditional rendering probe", 0, 0);
            if (window == 0) throw new IllegalStateException("glfwCreateWindow failed");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            say("renderer=%s version=%s", glGetString(GL_RENDERER), glGetString(GL_VERSION));
            program = program();
            glBindVertexArray(glGenVertexArrays());

            int target = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, target);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, SIZE, SIZE);
            int fbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, SIZE, SIZE);

            int[][] queries = {{GL_SAMPLES_PASSED, 0}, {GL_ANY_SAMPLES_PASSED, 0},
                               {GL_ANY_SAMPLES_PASSED_CONSERVATIVE, 0}};
            String[] names = {"samples", "any", "conservative"};
            // {mode, inverted, no_wait}
            int[][] modes = {{GL_QUERY_WAIT, 0, 0}, {GL_QUERY_NO_WAIT, 0, 1}, {GL_QUERY_BY_REGION_WAIT, 0, 0},
                             {GL_QUERY_WAIT_INVERTED, 1, 0}, {GL_QUERY_NO_WAIT_INVERTED, 1, 1}};
            String[] modeNames = {"wait", "no_wait", "by_region_wait", "wait_inverted", "no_wait_inverted"};
            for (int run = 0; run < 2; run++) {
                for (int q = 0; q < queries.length; q++) {
                    for (int m = 0; m < modes.length; m++) {
                        check(queries[q][0], modes[m][0], modes[m][1] != 0, modes[m][2] != 0,
                              names[q] + " " + modeNames[m] + " run=" + run, target);
                    }
                }
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            say("summary failures=%d %s", failures, failures == 0 ? "result=clean" : "result=LOSS");
            glViewport(0, 0, 640, 480);
            glClearColor(failures == 0 ? 0.05f : 0.6f, failures == 0 ? 0.4f : 0.05f, 0.05f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glfwSwapBuffers(window);
            glFinish();
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (window != 0) { GL.setCapabilities(null); glfwDestroyWindow(window); }
            glfwTerminate();
        }
        System.exit(0);
    }

    // A query that passed samples ("visible") and one that passed none ("hidden": the draw is scissored to
    // nothing). Under the visible one red fills the left half; under the hidden one green fills the right half
    // and a green clear is attempted. Not inverted: left red, right black. Inverted: left black, right green.
    private static void check(int queryType, int mode, boolean inverted, boolean noWait, String name, int target) {
        say("begin %s", name);
        int visible = glGenQueries(), hidden = glGenQueries();
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);

        glBeginQuery(queryType, visible);
        quad(-1, -1, 1, 1, BLACK);
        glEndQuery(queryType);
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, 0, 0, 0);
        glBeginQuery(queryType, hidden);
        quad(-1, -1, 1, 1, BLACK);
        glEndQuery(queryType);
        glDisable(GL_SCISSOR_TEST);

        glBeginConditionalRender(visible, mode);
        quad(-1, -1, 0, 1, RED);
        glEndConditionalRender();
        glBeginConditionalRender(hidden, mode);
        quad(0, -1, 1, 1, GREEN);
        glEnable(GL_SCISSOR_TEST);
        glScissor(SIZE / 2, 0, SIZE / 2, SIZE);
        glClearColor(0, 1, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
        glEndConditionalRender();
        glFinish();

        int left = inverted ? BLACK : RED, right = inverted ? GREEN : BLACK;
        // NO_WAIT lets the GL render unconditionally while the result is not ready (radeonsi asks the CP for
        // exactly that), so there both halves may also come out drawn - measured on the Pro, and allowed.
        ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        int badLeft = 0, badRight = 0, sample = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int pixel = pixels.getInt((y * SIZE + x) * 4);
                boolean isLeft = x < SIZE / 2;
                if (pixel != (isLeft ? left : right) && !(noWait && pixel == (isLeft ? RED : GREEN))) {
                    if (isLeft) badLeft++; else badRight++;
                    sample = pixel;
                }
            }
        }
        MemoryUtil.memFree(pixels);
        glDeleteQueries(visible);
        glDeleteQueries(hidden);
        boolean ok = badLeft == 0 && badRight == 0;
        if (!ok) failures++;
        say("end %s bad_left=%d bad_right=%d sample=%08x %s", name, badLeft, badRight, sample, ok ? "clean" : "LOSS");
    }

    private static void quad(float x0, float y0, float x1, float y1, int rgba) {
        glUseProgram(program);
        glUniform4f(glGetUniformLocation(program, "rect"), x0, y0, x1, y1);
        glUniform4f(glGetUniformLocation(program, "color"), (rgba & 255) / 255f, ((rgba >>> 8) & 255) / 255f,
                ((rgba >>> 16) & 255) / 255f, ((rgba >>> 24) & 255) / 255f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    private static int program() {
        int p = glCreateProgram();
        for (int[] stage : new int[][] {{GL_VERTEX_SHADER, 0}, {GL_FRAGMENT_SHADER, 1}}) {
            int s = glCreateShader(stage[0]);
            glShaderSource(s, stage[1] == 0 ? VS : FS);
            glCompileShader(s);
            if (glGetShaderi(s, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(glGetShaderInfoLog(s));
            glAttachShader(p, s);
        }
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(p));
        return p;
    }

    private static void say(String format, Object... values) {
        System.out.println("CR " + String.format(Locale.ROOT, format, values));
        System.out.flush();
    }
}
