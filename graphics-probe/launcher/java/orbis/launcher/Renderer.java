package orbis.launcher;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL30C.*;

/**
 * The launcher's drawing: solid rectangles, uploaded images and bundled Inter text,
 * batched into one core-profile shader. Coordinates are screen pixels with the
 * origin at the top left; {@link #s} scales a 1080-line layout to the framebuffer.
 */
class Renderer {
    /** AngelAuraMC's Amethyst palette: near-black surfaces, lighter bars, amethyst purple for what is chosen. */
    static final int BACKGROUND = 0xff181818, PANEL = 0xff232323, PANEL_HI = 0xff303030, ACCENT = 0xff9649b8,
            ACCENT_HI = 0xffb36ad6, ACCENT_DARK = 0xff6a2f85, GOOD = 0xff5fbf6a, BAR = 0xff222222, LINE = 0xff464646,
            TEXT = 0xffffffff, DIM = 0xffb2b2b2, WARN = 0xffe0b050, ERROR = 0xffd9534f, DARK = 0xff101010,
            WHITE = 0xffffffff, SHADE = 0xc0101010, CHIP = 0xff3a3a3a, BLUE = 0xff5b8fd6;
    /** Cells of the generated icon atlas: a disc for rounded corners and the four face buttons. */
    private static final int DISC = 0, RING = 1, CROSS = 2, TRIANGLE = 3, SQUARE = 4, ICON_CELLS = 5, ICON_SIZE = 64;

    int width, height;
    float s = 1f;
    private int program, vao, vbo, white, font, icons, art, screenUniform, textureUniform;
    final float[] glyphWidth = new float[256];
    private float fontQuadSize = 8, fontOffsetX, fontOffsetY;
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
        icons = iconAtlas();
        art = loadArtwork();
    }

    /** The application's artwork (its PS4 icon), or 0 when it is not bundled. */
    int artwork() {
        return art;
    }

    private int loadArtwork() throws IOException {
        try (InputStream in = Renderer.class.getResourceAsStream("art.png")) {
            if (in == null) return 0;
            BufferedImage image = ImageIO.read(in);
            int w = image.getWidth(), h = image.getHeight();
            ByteBuffer rgba = MemoryUtil.memAlloc(w * h * 4);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int argb = image.getRGB(x, y), i = (y * w + x) * 4;
                    rgba.put(i, (byte) (argb >> 16)).put(i + 1, (byte) (argb >> 8)).put(i + 2, (byte) argb)
                            .put(i + 3, (byte) (argb >>> 24));
                }
            int id = upload(w, h, rgba);
            MemoryUtil.memFree(rgba);
            return id;
        }
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

    /** An uploaded image (see {@link #upload}) stretched over the rectangle; color tints it (white: as is). */
    void image(int texture, float x, float y, float w, float h, int color) {
        bind(texture);
        quad(x, y, w, h, 0, 0, 1, 1, color);
    }

    /** Part of an uploaded image (texture coordinates u0..u1, v0..v1) over the rectangle. */
    void image(int texture, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int color) {
        bind(texture);
        quad(x, y, w, h, u0, v0, u1, v1, color);
    }

    /** Uploads RGBA pixels as a linearly filtered texture; returns its id for {@link #image}. */
    int upload(int w, int h, ByteBuffer rgba) {
        flush();
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        boundTexture = -1;
        return id;
    }

    void deleteTexture(int id) {
        flush();
        if (boundTexture == id) boundTexture = -1;
        glDeleteTextures(id);
    }

    /** Draws only inside the rectangle until {@link #unclip}; clips do not nest. */
    void clip(float x, float y, float w, float h) {
        flush();
        glEnable(GL_SCISSOR_TEST);
        int left = Math.max(0, (int) Math.floor(x)), top = Math.max(0, (int) Math.floor(y));
        int right = Math.min(width, (int) Math.ceil(x + w)), bottom = Math.min(height, (int) Math.ceil(y + h));
        glScissor(left, height - bottom, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    void unclip() {
        flush();
        glDisable(GL_SCISSOR_TEST);
    }

    /** A filled rectangle with rounded corners; without the icon atlas (host tests), square corners. */
    void roundRect(float x, float y, float w, float h, float radius, int color) {
        radius = Math.min(radius, Math.min(w, h) / 2);
        if (icons == 0 || radius < 1) { rect(x, y, w, h, color); return; }
        rect(x + radius, y, w - 2 * radius, h, color);
        rect(x, y + radius, radius, h - 2 * radius, color);
        rect(x + w - radius, y + radius, radius, h - 2 * radius, color);
        float u0 = (float) DISC / ICON_CELLS, um = (DISC + 0.5f) / ICON_CELLS, u1 = (DISC + 1f) / ICON_CELLS;
        image(icons, x, y, radius, radius, u0, 0, um, 0.5f, color);
        image(icons, x + w - radius, y, radius, radius, um, 0, u1, 0.5f, color);
        image(icons, x, y + h - radius, radius, radius, u0, 0.5f, um, 1, color);
        image(icons, x + w - radius, y + h - radius, radius, radius, um, 0.5f, u1, 1, color);
    }

    /** A filled circle of the given diameter. */
    void disc(float x, float y, float size, int color) {
        roundRect(x, y, size, size, size / 2, color);
    }

    /**
     * A controller button as the PS4 shows it: the face buttons as their symbols on a dark disc, anything else
     * (L1, R1, OPTIONS, the d-pad) as a labelled chip. Returns the width drawn.
     */
    float button(String name, float x, float y, float size) {
        int cell = switch (name) {
            case "cross" -> CROSS;
            case "circle" -> RING;
            case "triangle" -> TRIANGLE;
            case "square" -> SQUARE;
            default -> -1;
        };
        if (cell < 0) {
            float scale = size / 34f, w = width(name, scale) + size * 0.7f;
            roundRect(x, y, w, size, size * 0.3f, CHIP);
            text(name, x + size * 0.35f, y + size * 0.12f, scale, TEXT);
            return w;
        }
        int color = switch (cell) {
            case CROSS -> 0xff8ab4f0;
            case RING -> 0xffef7a7a;
            case TRIANGLE -> 0xff4fd1a5;
            default -> 0xffe59ae5;
        };
        disc(x, y, size, CHIP);
        if (icons == 0) text(name.substring(0, 1).toUpperCase(), x + size * 0.3f, y + size * 0.1f, size / 34f, color);
        else image(icons, x, y, size, size, (float) cell / ICON_CELLS, 0, (cell + 1f) / ICON_CELLS, 1, color);
        return size;
    }

    /** White shapes, antialiased from distance functions: the corner disc and the face-button symbols. */
    private int iconAtlas() {
        int n = ICON_SIZE, w = n * ICON_CELLS;
        ByteBuffer rgba = MemoryUtil.memAlloc(w * n * 4);
        for (int cell = 0; cell < ICON_CELLS; cell++)
            for (int py = 0; py < n; py++)
                for (int px = 0; px < n; px++) {
                    float x = px + 0.5f - n / 2f, y = py + 0.5f - n / 2f, d;
                    float stroke = 3.2f;
                    switch (cell) {
                        case DISC -> d = (float) Math.hypot(x, y) - n / 2f + 0.5f;
                        case RING -> d = Math.abs((float) Math.hypot(x, y) - 14) - stroke;
                        case CROSS -> d = Math.min(segment(x, y, -13, -13, 13, 13), segment(x, y, -13, 13, 13, -13)) - stroke;
                        case TRIANGLE -> d = Math.min(segment(x, y, 0, -14, 15, 12), Math.min(segment(x, y, 15, 12, -15, 12),
                                segment(x, y, -15, 12, 0, -14))) - stroke;
                        default -> d = Math.abs(Math.max(Math.abs(x), Math.abs(y)) - 12) - stroke;
                    }
                    float alpha = Math.max(0, Math.min(1, 0.5f - d));
                    int i = (py * w + cell * n + px) * 4;
                    rgba.put(i, (byte) -1).put(i + 1, (byte) -1).put(i + 2, (byte) -1).put(i + 3, (byte) Math.round(alpha * 255));
                }
        int id = upload(w, n, rgba);
        MemoryUtil.memFree(rgba);
        return id;
    }

    private static float segment(float x, float y, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
        return (float) Math.hypot(x - ax - t * dx, y - ay - t * dy);
    }

    /** A rectangle outline of the given thickness. */
    void frame(float x, float y, float w, float h, float thickness, int color) {
        rect(x, y, w, thickness, color);
        rect(x, y + h - thickness, w, thickness, color);
        rect(x, y, thickness, h, color);
        rect(x + w - thickness, y, thickness, h, color);
    }

    /** Text with its top-left at (x, y); scale is screen pixels per font pixel. Non-ASCII shows as '?'. */
    void text(String text, float x, float y, float scale, int color) {
        bind(font);
        for (int i = 0; i < text.length(); i++) {
            int c = glyph(text.charAt(i));
            float u = (c % 16) / 16f, v = (c / 16) / 16f;
            if (c != ' ') quad(x + fontOffsetX * scale, y + fontOffsetY * scale,
                    fontQuadSize * scale, fontQuadSize * scale, u, v, u + 1 / 16f, v + 1 / 16f, color);
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
        if (c < 256) return c;
        return switch (c) {
            case '\u2018', '\u2019', '\u201a', '\u2032' -> '\'';
            case '\u201c', '\u201d', '\u201e', '\u2033' -> '"';
            case '\u2013', '\u2014', '\u2212', '\u2010', '\u2011' -> '-';
            case '\u2022', '\u25cf', '\u2219' -> '*';
            case '\u2026' -> '.';
            case '\u2192' -> '>';
            case '\u2190' -> '<';
            default -> '?';
        };
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

    /**
     * Bundled Inter with fractional advances and smooth filtering. Older development JARs without
     * the atlas can still use Minecraft's ascii.png or the built-in console font.
     */
    private int loadFont() throws IOException {
        try (InputStream in = Renderer.class.getResourceAsStream("fonts/inter-atlas.png")) {
            if (in != null) {
                BufferedImage image = ImageIO.read(in);
                if (image == null || image.getWidth() != 640 || image.getHeight() != 640)
                    throw new IOException("Invalid bundled Inter atlas");
                try (InputStream metrics = Renderer.class.getResourceAsStream("fonts/inter-advances.bin")) {
                    if (metrics == null) throw new IOException("Missing Inter glyph advances");
                    DataInputStream data = new DataInputStream(metrics);
                    for (int c = 0; c < 256; c++) glyphWidth[c] = data.readFloat();
                }
                fontQuadSize = 10;
                fontOffsetX = -0.5f;
                fontOffsetY = -1.75f;
                int[] pixels = image.getRGB(0, 0, 640, 640, null, 0, 640);
                ByteBuffer rgba = MemoryUtil.memAlloc(640 * 640 * 4);
                try {
                    for (int argb : pixels)
                        rgba.put((byte) (argb >> 16)).put((byte) (argb >> 8))
                                .put((byte) argb).put((byte) (argb >>> 24));
                    rgba.flip();
                    return upload(640, 640, rgba);
                } finally {
                    MemoryUtil.memFree(rgba);
                }
            }
        }
        int w, h;
        int[] pixels;
        try (InputStream in = ClassLoader.getSystemResourceAsStream("assets/minecraft/textures/font/ascii.png")) {
            if (in != null) {
                BufferedImage image = ImageIO.read(in);
                w = image.getWidth();
                h = image.getHeight();
                pixels = image.getRGB(0, 0, w, h, null, 0, w);
            } else {
                w = h = ConsoleFont.SIZE;
                pixels = ConsoleFont.atlas();
            }
        }
        int cell = w / 16;
        ByteBuffer rgba = MemoryUtil.memAlloc(w * h * 4);
        for (int argb : pixels)
            rgba.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
        rgba.flip();
        for (int c = 0; c < 256; c++) {
            int last = -1;
            for (int x = 0; x < cell; x++)
                for (int y = 0; y < cell; y++)
                    if ((pixels[((c / 16) * cell + y) * w + (c % 16) * cell + x] >>> 24) != 0) last = x;
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
