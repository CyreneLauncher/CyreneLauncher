package orbis.probe;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/**
 * Geometry and tessellation shaders on the Pro's four shader engines, which nothing else this port runs has
 * exercised: the ESGS/GSVS rings and the tessellation factor and off-chip rings are all sized from the engine
 * count. Each test draws a grid of cells into a 512x512 RGBA8UI target - every cell made by a geometry shader or
 * a tessellated patch - and every pixel is compared with what the CPU says the cell holds: its primitive or patch
 * ID in red/green, a per-test tag in blue, and 255 in alpha only if the shader's own check of the data that
 * travelled through the rings passed. Lines start with GT for the log.
 */
public final class GeometryProbe {
    private static final int SIZE = 512;
    private static int failures;

    private GeometryProbe() {}

    public static void main(String[] args) throws java.io.IOException {
        String dump = System.getProperty("orbis.probe.dumpShaders");
        if (dump != null) { // host check: every generated stage, for glslangValidator
            String[] ext = {"vert", "geom", "tesc", "tese", "frag"};
            for (Test t : List.of(gsQuads(), gsHeavy(), gsInstanced(), tessQuads(), tessMax(), tessGs())) {
                String[] st = t.stages();
                String[] all = {st[0], st[1], st[2], st[3], st.length > 4 ? st[4] : FS};
                for (int i = 0; i < 5; i++)
                    if (all[i] != null)
                        java.nio.file.Files.writeString(java.nio.file.Path.of(dump, t.name() + "." + ext[i]),
                                all[i].replace("CELL", CELL).replace("TAG", Integer.toString(t.tag())));
            }
            return;
        }
        if (!Boolean.getBoolean("orbis.graphics.cuProbe"))
            throw new IllegalStateException("The geometry probe requires -Dorbis.graphics.cuProbe=true");
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        long window = 0;
        try {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(640, 480, "Orbis geometry probe", 0, 0);
            if (window == 0) throw new IllegalStateException("glfwCreateWindow failed");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            say("renderer=%s version=%s", glGetString(GL_RENDERER), glGetString(GL_VERSION));
            say("limits: gs_out_vertices=%d gs_out_components=%d gs_invocations=%d tess_gen_level=%d patch_vertices=%d",
                    glGetInteger(GL_MAX_GEOMETRY_OUTPUT_VERTICES), glGetInteger(GL_MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS),
                    glGetInteger(GL_MAX_GEOMETRY_SHADER_INVOCATIONS), glGetInteger(GL_MAX_TESS_GEN_LEVEL),
                    glGetInteger(GL_MAX_PATCH_VERTICES));

            int fbo = glGenFramebuffers(), target = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, target);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8UI, SIZE, SIZE);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("framebuffer incomplete");
            glViewport(0, 0, SIZE, SIZE);
            int vao = glGenVertexArrays();
            glBindVertexArray(vao);

            // orbis.probe.only=name,name narrows the run; orbis.probe.runs sets the repeat count.
            String only = System.getProperty("orbis.probe.only", "");
            List<Test> tests = new ArrayList<>();
            for (Test t : List.of(gsQuads(), gsHeavy(), gsInstanced(), tessQuads(), tessMax(), tessGs()))
                if (only.isEmpty() || List.of(only.split(",")).contains(t.name())) tests.add(t);
            for (int run = 0, runs = Integer.getInteger("orbis.probe.runs", 3); run < runs; run++)
                for (Test test : tests) run(test, run);

            say("summary failures=%d %s", failures, failures == 0 ? "result=clean" : "result=WRONG");
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glClearColor(failures == 0 ? 0.05f : 0.6f, failures == 0 ? 0.4f : 0.05f, 0.05f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glfwSwapBuffers(window);
            glFinish();
        } finally {
            if (window != 0) { GL.setCapabilities(null); glfwDestroyWindow(window); }
            glfwTerminate();
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------ the tests

    /** cells: grid width in cells; tag: blue; draw: issues the draw with the program bound. */
    private record Test(String name, int cells, int tag, String[] stages, Runnable draw, Expect expect) {}

    /** The id a pixel's cell must hold, or -1 when the pixel must stay clear. */
    @FunctionalInterface private interface Expect { int id(int cell, int cx, int cy, int px, int py, int cellPx); }

    private static final String VS = """
            #version 430 core
            out uint vid;
            void main() { vid = uint(gl_VertexID); gl_Position = vec4(0.0); }
            """;

    private static final String FS = """
            #version 430 core
            flat in uint id;
            flat in uint ok;
            layout(location=0) out uvec4 color;
            void main() { color = uvec4(id & 255u, id >> 8, TAGu, ok != 0u ? 255u : 1u); }
            """;

    /** Corner (u,v) of cell `cell` in an N x N grid, in clip space. */
    private static final String CELL = """
            vec4 corner(uint cell, uint n, vec2 uv) {
                vec2 c = vec2(float(cell % n), float(cell / n));
                return vec4((c + uv) / float(n) * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static Test gsQuads() {
        String gs = """
                #version 430 core
                layout(points) in;
                layout(triangle_strip, max_vertices=4) out;
                in uint vid[];
                flat out uint id;
                flat out uint ok;
                CELL
                void main() {
                    uint cell = vid[0];
                    for (int i = 0; i < 4; i++) {
                        id = uint(gl_PrimitiveIDIn); ok = cell == id ? 1u : 0u;
                        gl_Position = corner(cell, 64u, vec2(float(i & 1), float(i >> 1)));
                        EmitVertex();
                    }
                }
                """;
        return new Test("gs-quads", 64, 1, new String[]{VS, gs, null, null},
                () -> glDrawArrays(GL_POINTS, 0, 64 * 64), (cell, cx, cy, px, py, cp) -> cell);
    }

    /** 64 vertices of 14 components each (896, under GL's guaranteed 1024), for the GSVS ring. */
    private static Test gsHeavy() {
        String gs = """
                #version 430 core
                layout(points) in;
                layout(triangle_strip, max_vertices=64) out;
                in uint vid[];
                flat out uint id;
                flat out uint ok;
                flat out uvec4 pad[2];
                CELL
                void main() {
                    uint cell = vid[0];
                    for (uint s = 0u; s < 16u; s++) {
                        vec2 o = vec2(float(s % 4u), float(s / 4u)) * 0.25;
                        for (int i = 0; i < 4; i++) {
                            id = cell * 16u + s; ok = 1u;
                            for (uint k = 0u; k < 2u; k++) pad[k] = uvec4(id * 7u + k, id ^ k, ~id, k);
                            gl_Position = corner(cell, 32u, o + vec2(float(i & 1), float(i >> 1)) * 0.25);
                            EmitVertex();
                        }
                        EndPrimitive();
                    }
                }
                """;
        String fs = """
                #version 430 core
                flat in uint id;
                flat in uint ok;
                flat in uvec4 pad[2];
                layout(location=0) out uvec4 color;
                void main() {
                    bool good = ok != 0u;
                    for (uint k = 0u; k < 2u; k++) good = good && pad[k] == uvec4(id * 7u + k, id ^ k, ~id, k);
                    color = uvec4(id & 255u, id >> 8, TAGu, good ? 255u : 1u);
                }
                """;
        return new Test("gs-heavy", 32, 2, new String[]{VS, gs, null, null, fs},
                () -> glDrawArrays(GL_POINTS, 0, 32 * 32),
                (cell, cx, cy, px, py, cp) -> cell * 16 + (py * 4 / cp) * 4 + (px * 4 / cp));
    }

    private static Test gsInstanced() {
        String gs = """
                #version 430 core
                layout(points, invocations=4) in;
                layout(triangle_strip, max_vertices=4) out;
                in uint vid[];
                flat out uint id;
                flat out uint ok;
                CELL
                void main() {
                    uint cell = vid[0], q = uint(gl_InvocationID);
                    vec2 o = vec2(float(q & 1u), float(q >> 1u)) * 0.5;
                    for (int i = 0; i < 4; i++) {
                        id = cell * 4u + q; ok = cell == uint(gl_PrimitiveIDIn) ? 1u : 0u;
                        gl_Position = corner(cell, 64u, o + vec2(float(i & 1), float(i >> 1)) * 0.5);
                        EmitVertex();
                    }
                }
                """;
        return new Test("gs-instanced", 64, 3, new String[]{VS, gs, null, null},
                () -> glDrawArrays(GL_POINTS, 0, 64 * 64),
                (cell, cx, cy, px, py, cp) -> cell * 4 + (py * 2 / cp) * 2 + (px * 2 / cp));
    }

    private static final String TCS = """
            #version 430 core
            layout(vertices=1) out;
            in uint vid[];
            patch out uint pcell;
            patch out uvec4 pd0, pd1, pd2, pd3;
            void main() {
                uint cell = vid[0];
                pcell = cell;
                float l = LEVEL;
                gl_TessLevelOuter[0] = l; gl_TessLevelOuter[1] = l + 1.0;
                gl_TessLevelOuter[2] = l; gl_TessLevelOuter[3] = l + 2.0;
                gl_TessLevelInner[0] = l; gl_TessLevelInner[1] = l + 1.0;
                pd0 = uvec4(cell, cell * 3u, ~cell, 0u);       pd1 = uvec4(cell + 1u, cell * 3u, ~cell, 5u);
                pd2 = uvec4(cell + 2u, cell * 3u, ~cell, 10u); pd3 = uvec4(cell + 3u, cell * 3u, ~cell, 15u);
            }
            """;

    private static String tes(int cells, boolean toGs) {
        return """
                #version 430 core
                layout(quads, equal_spacing, ccw) in;
                patch in uint pcell;
                patch in uvec4 pd0, pd1, pd2, pd3;
                FLAT out uint OUTid;
                FLAT out uint OUTok;
                CELL
                void main() {
                    uint cell = pcell;
                    bool good = cell == uint(gl_PrimitiveID);
                    good = good && pd0 == uvec4(cell, cell * 3u, ~cell, 0u) && pd1 == uvec4(cell + 1u, cell * 3u, ~cell, 5u)
                                && pd2 == uvec4(cell + 2u, cell * 3u, ~cell, 10u) && pd3 == uvec4(cell + 3u, cell * 3u, ~cell, 15u);
                    OUTid = cell; OUTok = good ? 1u : 0u;
                    gl_Position = corner(cell, NNu, gl_TessCoord.xy);
                }
                """.replace("NN", Integer.toString(cells)).replace("OUT", toGs ? "t" : "")
                .replace("FLAT ", toGs ? "" : "flat ");
    }

    private static Test tessQuads() {
        // Levels 1..16 by cell, so neighbouring patches tessellate differently.
        String tcs = TCS.replace("LEVEL", System.getProperty("orbis.probe.tessLevel", "1.0 + float(cell % 16u)"));
        return new Test("tess-quads", 64, 4, new String[]{VS, null, tcs, tes(64, false)},
                () -> { glPatchParameteri(GL_PATCH_VERTICES, 1); glDrawArrays(GL_PATCHES, 0, 64 * 64); },
                (cell, cx, cy, px, py, cp) -> cell);
    }

    private static Test tessMax() {
        String tcs = TCS.replace("LEVEL", "62.0"); // outer up to 64
        return new Test("tess-max", 16, 5, new String[]{VS, null, tcs, tes(16, false)},
                () -> { glPatchParameteri(GL_PATCH_VERTICES, 1); glDrawArrays(GL_PATCHES, 0, 16 * 16); },
                (cell, cx, cy, px, py, cp) -> cell);
    }

    private static Test tessGs() {
        String tcs = TCS.replace("LEVEL", "1.0 + float(cell % 8u)");
        String gs = """
                #version 430 core
                layout(triangles) in;
                layout(triangle_strip, max_vertices=3) out;
                in uint tid[];
                in uint tok[];
                flat out uint id;
                flat out uint ok;
                void main() {
                    for (int i = 0; i < 3; i++) {
                        id = tid[0] ^ 0x55u; ok = tok[0] != 0u && tid[i] == tid[0] ? 1u : 0u;
                        gl_Position = gl_in[i].gl_Position;
                        EmitVertex();
                    }
                }
                """;
        return new Test("tess-gs", 64, 6, new String[]{VS, gs, tcs, tes(64, true)},
                () -> { glPatchParameteri(GL_PATCH_VERTICES, 1); glDrawArrays(GL_PATCHES, 0, 64 * 64); },
                (cell, cx, cy, px, py, cp) -> cell ^ 0x55);
    }

    // ------------------------------------------------------------------ running and checking

    private static void run(Test test, int run) {
        String name = test.name() + " run=" + run;
        say("%s start", name); // a line before the draw, so a GPU hang names its test
        System.out.flush();
        String fs = test.stages().length > 4 ? test.stages()[4] : FS;
        int program = program(test.stages()[0], test.stages()[1], test.stages()[2], test.stages()[3], fs, test.tag());
        glClearBufferuiv(GL_COLOR, 0, new int[]{0, 0, 0, 0});
        glUseProgram(program);
        long t0 = System.nanoTime();
        test.draw().run();
        glFinish();
        long us = (System.nanoTime() - t0) / 1000;
        ByteBuffer px = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE, px);
        int error = glGetError();
        int cellPx = SIZE / test.cells();
        int wrong = 0, notOk = 0, empty = 0, wrongCells = 0;
        boolean[] cellBad = new boolean[test.cells() * test.cells()];
        char[] cellMark = new char[test.cells() * test.cells()];
        java.util.Arrays.fill(cellMark, '.');
        List<String> examples = new ArrayList<>();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int cx = x / cellPx, cy = y / cellPx, cell = cy * test.cells() + cx;
                int want = test.expect().id(cell, cx, cy, x % cellPx, y % cellPx, cellPx);
                int o = (y * SIZE + x) * 4;
                int r = px.get(o) & 255, g = px.get(o + 1) & 255, b = px.get(o + 2) & 255, a = px.get(o + 3) & 255;
                int got = r | (g << 8);
                boolean ok = b == test.tag() && a == 255 && got == (want & 0xffff);
                if (ok) continue;
                wrong++;
                if (a == 0) empty++; else if (a != 255) notOk++;
                if (!cellBad[cell]) { cellBad[cell] = true; wrongCells++; }
                cellMark[cell] = a == 0 ? (cellMark[cell] == 'x' ? 'x' : ' ') : 'x';
                if (examples.size() < 6)
                    examples.add(String.format(Locale.ROOT, "(%d,%d) want %d got %d/%d/%d/%d", x, y, want, r, g, b, a));
            }
        }
        MemoryUtil.memFree(px);
        glDeleteProgram(program);
        boolean clean = wrong == 0 && error == GL_NO_ERROR;
        if (!clean) failures++;
        say("%s %s draw_us=%d pixels_wrong=%d cells_wrong=%d/%d empty=%d check_failed=%d gl_error=0x%x %s", name,
                clean ? "clean" : "WRONG", us, wrong, wrongCells, cellBad.length, empty, notOk, error,
                examples.isEmpty() ? "" : String.join("; ", examples));
        // Which cells, as a picture: '.' right, ' ' empty, 'x' wrong. Cell index = row * cells + column, row 0
        // at the BOTTOM of the target (GL's origin), printed top row first. Cell index is also primitive order.
        if (!clean && run < 2) {
            for (int cy = test.cells() - 1; cy >= 0; cy--)
                say("%s map %3d |%s|", name, cy, new String(cellMark, cy * test.cells(), test.cells()));
            // And by primitive order in runs of 64 (radeonsi's primgroup with a GS): one char per group.
            StringBuilder groups = new StringBuilder();
            for (int g = 0; g * 64 < cellMark.length; g++) {
                int bad = 0;
                for (int i = g * 64; i < Math.min(cellMark.length, g * 64 + 64); i++) if (cellMark[i] != '.') bad++;
                groups.append(bad == 0 ? '.' : bad == 64 ? '#' : '+');
            }
            say("%s groups-of-64 |%s|", name, groups);
        }
    }

    private static int program(String vs, String gs, String tcs, String tes, String fs, int tag) {
        int program = glCreateProgram();
        List<Integer> shaders = new ArrayList<>();
        shaders.add(shader(GL_VERTEX_SHADER, vs));
        if (tcs != null) shaders.add(shader(GL_TESS_CONTROL_SHADER, tcs));
        if (tes != null) shaders.add(shader(GL_TESS_EVALUATION_SHADER, tes));
        if (gs != null) shaders.add(shader(GL_GEOMETRY_SHADER, gs));
        shaders.add(shader(GL_FRAGMENT_SHADER, fs.replace("TAG", Integer.toString(tag))));
        for (int s : shaders) glAttachShader(program, s);
        glLinkProgram(program);
        for (int s : shaders) glDeleteShader(s);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
        return program;
    }

    private static int shader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source.replace("CELL", CELL));
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
            throw new IllegalStateException("shader 0x" + Integer.toHexString(type) + ": " + glGetShaderInfoLog(shader));
        return shader;
    }

    private static void say(String format, Object... values) {
        System.out.println("GT " + String.format(Locale.ROOT, format, values));
    }
}
