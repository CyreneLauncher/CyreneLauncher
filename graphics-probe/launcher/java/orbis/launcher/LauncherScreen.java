package orbis.launcher;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import orbis.graphics.NativePlatform;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30C.*;

/**
 * The launcher's own screen: the instance with Play, Settings and Quit, driven by the gamepad
 * (d-pad or left stick, cross to confirm, circle to go back) or the keyboard (arrows, Enter,
 * Escape). Drawn with a core-profile shader: solid rectangles and Minecraft's ascii.png font, read
 * from the game JAR on the class path.
 */
final class LauncherScreen {
    private static final int BACKGROUND = 0xff16181d, PANEL = 0xff23262e, ACCENT = 0xff5aa864,
            TEXT = 0xffe8e8e8, DIM = 0xff9aa0a6, WARN = 0xffe0b050;

    private final LauncherConfig config;
    private long window;
    private int width, height;
    private int program, vao, vbo, white, font;
    private final int[] glyphWidth = new int[256];
    private FloatBuffer vertices = MemoryUtil.memAllocFloat(6 * 8 * 4096);
    private int vertexCount;

    private enum Page { HOME, SETTINGS }
    private Page page = Page.HOME;
    private int selected;
    private String status = "";
    private int statusColor = DIM;
    private boolean dirty;

    // Input edges: one action per press, with repeat while held.
    private final boolean[] held = new boolean[6];
    private final long[] repeatAt = new long[6];
    private static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3, CONFIRM = 4, BACK = 5;
    private final List<Integer> pendingKeys = new ArrayList<>();

    LauncherScreen(LauncherConfig config) {
        this.config = config;
    }

    void run() throws IOException {
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(1920, 1080, "Orbis Launcher", 0, 0);
        if (window == 0) throw new IllegalStateException("window creation failed");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (action != GLFW_RELEASE) pendingKeys.add(key);
        });
        setUp();
        try (GLFWGamepadState pad = GLFWGamepadState.calloc()) {
            while (!glfwWindowShouldClose(window)) {
                glfwPollEvents();
                handleInput(pad);
                int[] w = new int[1], h = new int[1];
                glfwGetFramebufferSize(window, w, h);
                width = w[0];
                height = h[0];
                draw();
                glfwSwapBuffers(window);
            }
        }
        glfwDestroyWindow(window);
        glfwTerminate();
        System.exit(0);
    }

    // ------------------------------------------------------------------ actions

    private List<String> items() {
        List<String> items = new ArrayList<>();
        if (page == Page.HOME) {
            items.add("Play");
            items.add("Settings");
            items.add("Quit");
        } else {
            items.add("Java heap        < " + config.heapMib + " MiB >");
            items.add("GPU memory       < " + config.arenaMib + " MiB >");
            items.add("Debug log        < " + (config.debugLog ? "on" : "off") + " >");
            items.add("Save");
            items.add("Back");
        }
        return items;
    }

    private void confirm() {
        if (page == Page.HOME) {
            switch (selected) {
                case 0 -> play();
                case 1 -> { page = Page.SETTINGS; selected = 0; status = ""; }
                default -> glfwSetWindowShouldClose(window, true);
            }
        } else {
            switch (selected) {
                case 3 -> save();
                case 4 -> back();
                default -> adjust(+1);
            }
        }
    }

    private void back() {
        if (page == Page.SETTINGS) {
            page = Page.HOME;
            selected = 1;
            if (dirty) setStatus("Unsaved changes - open Settings and choose Save", WARN);
        }
    }

    private void adjust(int direction) {
        if (page != Page.SETTINGS) return;
        int step = direction * LauncherConfig.STEP_MIB;
        switch (selected) {
            case 0 -> {
                int heap = clamp(config.heapMib + step, LauncherConfig.MIN_HEAP_MIB, LauncherConfig.MAX_HEAP_MIB);
                if (heap + config.arenaMib <= LauncherConfig.MAX_TOTAL_MIB) config.heapMib = heap;
                else setStatus("Heap and GPU memory together are limited to " + LauncherConfig.MAX_TOTAL_MIB + " MiB", WARN);
            }
            case 1 -> {
                int arena = clamp(config.arenaMib + step, LauncherConfig.MIN_ARENA_MIB, LauncherConfig.MAX_ARENA_MIB);
                if (config.heapMib + arena <= LauncherConfig.MAX_TOTAL_MIB) config.arenaMib = arena;
                else setStatus("Heap and GPU memory together are limited to " + LauncherConfig.MAX_TOTAL_MIB + " MiB", WARN);
            }
            case 2 -> config.debugLog = !config.debugLog;
            default -> { return; }
        }
        dirty = true;
    }

    private void save() {
        try {
            config.save();
            dirty = false;
            setStatus("Saved. Applies when you press Play.", ACCENT);
        } catch (IOException failure) {
            setStatus("Saving failed: " + failure.getMessage(), WARN);
        }
    }

    private void play() {
        try {
            if (dirty) config.save();
            config.writeNextStart();
        } catch (IOException failure) {
            setStatus("Cannot start: " + failure.getMessage(), WARN);
            return;
        }
        setStatus("Starting Minecraft...", ACCENT);
        draw();
        glfwSwapBuffers(window);
        int error = NativePlatform.nRelaunch();
        try {
            java.nio.file.Files.deleteIfExists(config.nextStart());
        } catch (IOException ignored) {
        }
        setStatus(String.format("Restart failed (0x%08x)", error), WARN);
    }

    private void setStatus(String text, int color) {
        status = text;
        statusColor = color;
    }

    // ------------------------------------------------------------------ input

    private void handleInput(GLFWGamepadState pad) {
        boolean[] now = new boolean[6];
        if (glfwJoystickIsGamepad(GLFW_JOYSTICK_1) && glfwGetGamepadState(GLFW_JOYSTICK_1, pad)) {
            float x = pad.axes(GLFW_GAMEPAD_AXIS_LEFT_X), y = pad.axes(GLFW_GAMEPAD_AXIS_LEFT_Y);
            now[UP] = pad.buttons(GLFW_GAMEPAD_BUTTON_DPAD_UP) == GLFW_PRESS || y < -0.6f;
            now[DOWN] = pad.buttons(GLFW_GAMEPAD_BUTTON_DPAD_DOWN) == GLFW_PRESS || y > 0.6f;
            now[LEFT] = pad.buttons(GLFW_GAMEPAD_BUTTON_DPAD_LEFT) == GLFW_PRESS || x < -0.6f;
            now[RIGHT] = pad.buttons(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT) == GLFW_PRESS || x > 0.6f;
            now[CONFIRM] = pad.buttons(GLFW_GAMEPAD_BUTTON_A) == GLFW_PRESS;
            now[BACK] = pad.buttons(GLFW_GAMEPAD_BUTTON_B) == GLFW_PRESS;
        }
        long t = System.nanoTime();
        for (int i = 0; i < 6; i++) {
            boolean fire = now[i] && (!held[i] || (i < CONFIRM && t >= repeatAt[i]));
            if (now[i] && !held[i]) repeatAt[i] = t + 400_000_000L;
            else if (fire) repeatAt[i] = t + 120_000_000L;
            held[i] = now[i];
            if (fire) act(i);
        }
        for (int key : pendingKeys) {
            switch (key) {
                case GLFW_KEY_UP -> act(UP);
                case GLFW_KEY_DOWN -> act(DOWN);
                case GLFW_KEY_LEFT -> act(LEFT);
                case GLFW_KEY_RIGHT -> act(RIGHT);
                case GLFW_KEY_ENTER, GLFW_KEY_SPACE -> act(CONFIRM);
                case GLFW_KEY_ESCAPE, GLFW_KEY_BACKSPACE -> act(BACK);
                default -> { }
            }
        }
        pendingKeys.clear();
    }

    private void act(int action) {
        int count = items().size();
        switch (action) {
            case UP -> selected = (selected + count - 1) % count;
            case DOWN -> selected = (selected + 1) % count;
            case LEFT -> adjust(-1);
            case RIGHT -> adjust(+1);
            case CONFIRM -> confirm();
            default -> back();
        }
    }

    // ------------------------------------------------------------------ drawing

    private void draw() {
        glViewport(0, 0, width, height);
        glClearColor(((BACKGROUND >> 16) & 255) / 255f, ((BACKGROUND >> 8) & 255) / 255f,
                (BACKGROUND & 255) / 255f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
        float s = height / 1080f;

        // Title and instance card.
        text("ORBIS LAUNCHER", 120 * s, 90 * s, 8 * s, TEXT);
        text("Minecraft for PlayStation 4", 124 * s, 170 * s, 3 * s, DIM);
        rect(120 * s, 250 * s, 760 * s, 220 * s, PANEL);
        rect(120 * s, 250 * s, 8 * s, 220 * s, ACCENT);
        text("Minecraft " + config.versionId, 160 * s, 280 * s, 4 * s, TEXT);
        text(config.modCount() + " mods  |  Fabric", 160 * s, 340 * s, 3 * s, DIM);
        text("Heap " + config.heapMib + " MiB  |  GPU " + config.arenaMib + " MiB", 160 * s, 390 * s, 3 * s, DIM);

        // Menu.
        List<String> items = items();
        float y = 540 * s;
        if (page == Page.SETTINGS) text("SETTINGS", 120 * s, 500 * s, 4 * s, TEXT);
        if (page == Page.SETTINGS) y = 570 * s;
        for (int i = 0; i < items.size(); i++) {
            boolean on = i == selected;
            if (on) rect(120 * s, y - 10 * s, 760 * s, 60 * s, ACCENT);
            text(items.get(i), 150 * s, y, 4 * s, on ? 0xff101010 : TEXT);
            y += 70 * s;
        }
        if (!status.isEmpty()) text(status, 120 * s, height - 160 * s, 3 * s, statusColor);
        text("Cross: select   Circle: back   D-pad: move/adjust", 120 * s, height - 90 * s, 2.5f * s, DIM);
        flush();
    }

    // ------------------------------------------------------------------ GL

    private void setUp() throws IOException {
        String vertex = "#version 150\nin vec2 pos; in vec2 uv; in vec4 color; uniform vec2 screen;\n"
                + "out vec2 vUv; out vec4 vColor;\n"
                + "void main() { vUv = uv; vColor = color; gl_Position = vec4(pos.x / screen.x * 2.0 - 1.0,"
                + " 1.0 - pos.y / screen.y * 2.0, 0.0, 1.0); }\n";
        String fragment = "#version 150\nin vec2 vUv; in vec4 vColor; uniform sampler2D tex; out vec4 frag;\n"
                + "void main() { frag = texture(tex, vUv) * vColor; if (frag.a < 0.01) discard; }\n";
        program = glCreateProgram();
        glAttachShader(program, shader(GL_VERTEX_SHADER, vertex));
        glAttachShader(program, shader(GL_FRAGMENT_SHADER, fragment));
        glBindAttribLocation(program, 0, "pos");
        glBindAttribLocation(program, 1, "uv");
        glBindAttribLocation(program, 2, "color");
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException("launcher shader: " + glGetProgramInfoLog(program));
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 32, 0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 32, 8);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, 32, 16);
        for (int i = 0; i < 3; i++) glEnableVertexAttribArray(i);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        ByteBuffer pixel = MemoryUtil.memAlloc(4).put(0, (byte) -1).put(1, (byte) -1).put(2, (byte) -1).put(3, (byte) -1);
        white = texture(1, 1, pixel);
        MemoryUtil.memFree(pixel);
        font = loadFont();
    }

    private int shader(int type, String source) {
        int id = glCreateShader(type);
        glShaderSource(id, source);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE)
            throw new IllegalStateException("launcher shader: " + glGetShaderInfoLog(id));
        return id;
    }

    private int texture(int w, int h, ByteBuffer rgba) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        return id;
    }

    /** Minecraft's 16x16-glyph ascii.png; a glyph's width runs to its last opaque column. */
    private int loadFont() throws IOException {
        BufferedImage image;
        try (InputStream in = ClassLoader.getSystemResourceAsStream("assets/minecraft/textures/font/ascii.png")) {
            if (in == null) throw new IOException("ascii.png is not on the class path");
            image = ImageIO.read(in);
        }
        int w = image.getWidth(), h = image.getHeight(), cell = w / 16;
        ByteBuffer rgba = MemoryUtil.memAlloc(w * h * 4);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                rgba.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        rgba.flip();
        for (int c = 0; c < 256; c++) {
            int last = -1;
            for (int x = 0; x < cell; x++)
                for (int y = 0; y < cell; y++)
                    if ((image.getRGB((c % 16) * cell + x, (c / 16) * cell + y) >>> 24) != 0) last = x;
            glyphWidth[c] = (last + 2) * 8 / cell;
        }
        glyphWidth[' '] = 4;
        int id = texture(w, h, rgba);
        MemoryUtil.memFree(rgba);
        return id;
    }

    private int boundTexture = -1;

    private void bind(int textureId) {
        if (textureId != boundTexture) {
            flush();
            boundTexture = textureId;
        }
    }

    private void rect(float x, float y, float w, float h, int color) {
        bind(white);
        quad(x, y, w, h, 0, 0, 1, 1, color);
    }

    /** Text with its top-left at (x, y); scale is screen pixels per font pixel. */
    private void text(String text, float x, float y, float scale, int color) {
        bind(font);
        for (int i = 0; i < text.length(); i++) {
            int c = text.charAt(i) & 0xff;
            float u = (c % 16) / 16f, v = (c / 16) / 16f;
            if (c != ' ') quad(x, y, 8 * scale, 8 * scale, u, v, u + 1 / 16f, v + 1 / 16f, color);
            x += glyphWidth[c] * scale;
        }
    }

    private void quad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, int color) {
        if (vertices.remaining() < 48) flush();
        float r = ((color >> 16) & 255) / 255f, g = ((color >> 8) & 255) / 255f, b = (color & 255) / 255f,
                a = ((color >>> 24) & 255) / 255f;
        float[][] corners = {{x, y, u0, v0}, {x + w, y, u1, v0}, {x + w, y + h, u1, v1},
                {x, y, u0, v0}, {x + w, y + h, u1, v1}, {x, y + h, u0, v1}};
        for (float[] p : corners) vertices.put(p[0]).put(p[1]).put(p[2]).put(p[3]).put(r).put(g).put(b).put(a);
        vertexCount += 6;
    }

    private void flush() {
        if (vertexCount == 0) return;
        vertices.flip();
        glUseProgram(program);
        glUniform2f(glGetUniformLocation(program, "screen"), width, height);
        glUniform1i(glGetUniformLocation(program, "tex"), 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, boundTexture);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        vertices.clear();
        vertexCount = 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
