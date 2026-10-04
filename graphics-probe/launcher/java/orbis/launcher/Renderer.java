package orbis.launcher;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL30C.*;

/**
 * The launcher's drawing: solid rectangles and Minecraft's ascii.png font (read from the game JAR
 * on the class path), batched into one core-profile shader. Coordinates are screen pixels with the
 * origin at the top left; {@link #s} scales a 1080-line layout to the framebuffer.
 */
class Renderer {
    static final int BACKGROUND = 0xff16181d, PANEL = 0xff23262e, PANEL_HI = 0xff2e323c, ACCENT = 0xff5aa864,
            TEXT = 0xffe8e8e8, DIM = 0xff9aa0a6, WARN = 0xffe0b050, ERROR = 0xffd9534f, DARK = 0xff101010;

    int width, height;
    float s = 1f;
    private int program, vao, vbo, white, font, screenUniform, textureUniform;
    final int[] glyphWidth = new int[256];
    private final FloatBuffer vertices = MemoryUtil.memAllocFloat(6 * 8 * 4096);
    private int vertexCount, boundTexture = -1;

    void setUp() throws IOException {
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
        screenUniform = glGetUniformLocation(program, "screen");
        textureUniform = glGetUniformLocation(program, "tex");
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

        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        for (int i = 0; i < 4; i++) pixel.put(i, (byte) -1);
        white = texture(1, 1, pixel);
        MemoryUtil.memFree(pixel);
        font = loadFont();
    }

    void begin(int framebufferWidth, int framebufferHeight) {
        width = framebufferWidth;
        height = framebufferHeight;
        s = height / 1080f;
        glViewport(0, 0, width, height);
        glClearColor(((BACKGROUND >> 16) & 255) / 255f, ((BACKGROUND >> 8) & 255) / 255f,
                (BACKGROUND & 255) / 255f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    void end() {
        flush();
    }

    void rect(float x, float y, float w, float h, int color) {
        bind(white);
        quad(x, y, w, h, 0, 0, 1, 1, color);
    }

    /** Text with its top-left at (x, y); scale is screen pixels per font pixel. Non-ASCII shows as '?'. */
    void text(String text, float x, float y, float scale, int color) {
        bind(font);
        for (int i = 0; i < text.length(); i++) {
            int c = glyph(text.charAt(i));
            float u = (c % 16) / 16f, v = (c / 16) / 16f;
            if (c != ' ') quad(x, y, 8 * scale, 8 * scale, u, v, u + 1 / 16f, v + 1 / 16f, color);
            x += glyphWidth[c] * scale;
        }
    }

    float width(String text, float scale) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) w += glyphWidth[glyph(text.charAt(i))] * scale;
        return w;
    }

    /** Text cut with "..." to fit maxWidth. */
    String fit(String text, float maxWidth, float scale) {
        if (width(text, scale) <= maxWidth) return text;
        int end = text.length();
        while (end > 0 && width(text.substring(0, end) + "...", scale) > maxWidth) end--;
        return text.substring(0, end) + "...";
    }

    /** Word wrap to maxWidth; a word longer than a line (a path, a class name) is cut. */
    List<String> wrap(String text, float maxWidth, float scale) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (width(candidate, scale) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (line.length() > 0) out.add(line.toString());
            line.setLength(0);
            while (width(word, scale) > maxWidth && word.length() > 1) {
                int cut = word.length();
                while (cut > 1 && width(word.substring(0, cut), scale) > maxWidth) cut--;
                out.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static int glyph(char c) {
        return c < 256 ? c : '?';
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

    private void bind(int textureId) {
        if (textureId != boundTexture) {
            flush();
            boundTexture = textureId;
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
        glUniform2f(screenUniform, width, height);
        glUniform1i(textureUniform, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, boundTexture);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        vertices.clear();
        vertexCount = 0;
    }
}
