package orbis.probe;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.opengl.GL44C.glBufferStorage;
import static org.lwjgl.opengl.GL44C.glClearTexImage;
import static org.lwjgl.opengl.GL44C.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44C.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL44C.GL_MAP_READ_BIT;

/**
 * Characterises how compute output goes missing on radeonsi. Each workgroup's lane 0 records, with atomics,
 * that it ran and on which CU (the opt-in ACO hardware-ID hook makes clock2x32ARB return SQ_WAVE_HW_ID);
 * every lane also writes a plain store. Missing stores whose workgroup has an atomic record ran but lost
 * their writes; missing records mean the work never ran. A second read after glFinish and a pause tells
 * late writes (synchronisation) from lost ones. Lines start with CT for the log.
 */
public final class ComputeProbe {
    private static final int TAG = 0x43555052;
    private static int failures;

    private ComputeProbe() {}

    public static void main(String[] args) {
        if (!Boolean.getBoolean("orbis.graphics.cuProbe"))
            throw new IllegalStateException("The compute probe requires -Dorbis.graphics.cuProbe=true");
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        long window = 0;
        try {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(640, 480, "Orbis compute probe", 0, 0);
            if (window == 0) throw new IllegalStateException("glfwCreateWindow failed");
            glfwMakeContextCurrent(window);
            if (!GL.createCapabilities().GL_ARB_shader_clock) throw new IllegalStateException("no GL_ARB_shader_clock");
            say("renderer=%s version=%s", glGetString(GL_RENDERER), glGetString(GL_VERSION));
            say("AMD_DEBUG=%s", System.getProperty("orbis.graphics.env.AMD_DEBUG", "(unset)"));
            for (int run = 0; run < 3; run++) {
                buffers(64, 1024, false, run);
                buffers(64, 16384, false, run);
                buffers(256, 4096, false, run);
                buffers(64, 16384, true, run);
                image(run);
                internal(run);
                bufferOps(run);
                colorBlock(run);
            }
            say("summary failures=%d %s", failures, failures == 0 ? "result=clean" : "result=LOSS");
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

    // ------------------------------------------------------------------ buffer stores

    private static final String BUFFER_SHADER = """
            #version 430 core
            #extension GL_ARB_shader_clock : require
            layout(local_size_x=WG) in;
            layout(std430,binding=0) buffer Out { uint outv[]; };
            layout(std430,binding=1) buffer Rec { uint rec[]; };
            layout(std430,binding=2) buffer Cnt { uint cnt[]; };
            uniform uint seed;
            void main() {
                uint g = gl_WorkGroupID.x, l = gl_LocalInvocationID.x, i = gl_GlobalInvocationID.x;
                outv[i] = (i * 2654435761u) ^ seed;
                if (l == 0u) {
                    uvec2 hw = clock2x32ARB();
                    atomicExchange(rec[g], (hw.x & 0x7fffffffu) | 0x80000000u);
                    atomicAdd(cnt[0], 1u);
                    if (hw.y != 0x43555052u) atomicAdd(cnt[1], 1u);
                    uint slot = ((hw.x >> 13) & 3u) * 32u + ((hw.x >> 12) & 1u) * 16u + ((hw.x >> 8) & 15u);
                    atomicAdd(cnt[2u + slot], 1u);
                }
            }
            """;

    private static void buffers(int wg, int groups, boolean finishFirst, int run) {
        int program = compute(BUFFER_SHADER.replace("WG", Integer.toString(wg)));
        int threads = wg * groups;
        int seed = 0x5a5a0000 + run * 977 + wg + groups;
        int out = buffer(0, (long) threads * 4), rec = buffer(1, (long) groups * 4), cnt = buffer(2, 130 * 4);
        glUseProgram(program);
        glUniform1ui(glGetUniformLocation(program, "seed"), seed);
        glDispatchCompute(groups, 1, 1);
        if (finishFirst) glFinish();
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        String name = String.format(Locale.ROOT, "buffer wg=%d groups=%d%s run=%d", wg, groups,
                finishFirst ? " finish-first" : "", run);
        int[] first = check(name + " read=1", out, rec, cnt, wg, groups, seed);
        glFinish();
        try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        int[] second = check(name + " read=2", out, rec, cnt, wg, groups, seed);
        if (first[0] != second[0])
            say("%s LATE: %d missing stores at the first read, %d after glFinish and 300 ms", name, first[0], second[0]);
        glDeleteBuffers(new int[]{out, rec, cnt});
        glDeleteProgram(program);
    }

    /** Returns {missing stores, groups without a record}. */
    private static int[] check(String name, int out, int rec, int cnt, int wg, int groups, int seed) {
        IntBuffer o = read(out, wg * groups), r = read(rec, groups), c = read(cnt, 130);
        int missingStores = 0, emptyGroups = 0, partialGroups = 0, unrecorded = 0, recordedButLost = 0;
        Map<String, Integer> lostByCu = new TreeMap<>(), ranByCu = new TreeMap<>();
        List<String> examples = new ArrayList<>();
        for (int g = 0; g < groups; g++) {
            int bad = 0;
            for (int l = 0; l < wg; l++) {
                int i = g * wg + l;
                if (o.get(i) != ((int) (i * 2654435761L) ^ seed)) bad++;
            }
            int record = r.get(g);
            String cu = record < 0 ? cu(record) : "none";
            if (record < 0) ranByCu.merge(cu, 1, Integer::sum);
            else unrecorded++;
            if (bad == 0) continue;
            missingStores += bad;
            if (bad == wg) emptyGroups++; else partialGroups++;
            if (record < 0) { recordedButLost++; lostByCu.merge(cu, 1, Integer::sum); }
            if (examples.size() < 6) examples.add(g + ":" + bad + "/" + wg + "@" + cu);
        }
        boolean clean = missingStores == 0 && unrecorded == 0 && c.get(0) == groups && c.get(1) == 0;
        if (!clean) failures++;
        say("%s stores_missing=%d groups_empty=%d groups_partial=%d unrecorded=%d recorded_but_lost=%d atomics=%d/%d tag_bad=%d %s",
                name, missingStores, emptyGroups, partialGroups, unrecorded, recordedButLost, c.get(0), groups, c.get(1),
                clean ? "clean" : "LOSS");
        if (!clean) {
            say("%s examples(group:bad/lanes@se.sh.cu)=%s", name, examples);
            say("%s lost_by_cu=%s", name, lostByCu);
            say("%s ran_by_cu=%s", name, ranByCu);
        }
        MemoryUtil.memFree(o); MemoryUtil.memFree(r); MemoryUtil.memFree(c);
        return new int[]{missingStores, unrecorded};
    }

    // ------------------------------------------------------------------ image stores into a tiled texture

    private static final String IMAGE_SHADER = """
            #version 430 core
            #extension GL_ARB_shader_clock : require
            layout(local_size_x=8, local_size_y=8) in;
            layout(rgba8, binding=0) uniform writeonly image2D img;
            layout(std430,binding=1) buffer Rec { uint rec[]; };
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                imageStore(img, p, vec4(float(p.x & 255) / 255.0, float(p.y & 255) / 255.0, float((p.x ^ p.y) & 255) / 255.0, 1.0));
                if (gl_LocalInvocationIndex == 0u) {
                    uvec2 hw = clock2x32ARB();
                    atomicExchange(rec[gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x], (hw.x & 0x7fffffffu) | 0x80000000u);
                }
            }
            """;

    private static void image(int run) {
        final int size = 1024, blocks = size / 8;
        int program = compute(IMAGE_SHADER);
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, size, size);
        ByteBuffer zero = MemoryUtil.memCalloc(size * size * 4);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, size, size, GL_RGBA, GL_UNSIGNED_BYTE, zero);
        int rec = buffer(1, (long) blocks * blocks * 4);
        glBindImageTexture(0, texture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        glUseProgram(program);
        glDispatchCompute(blocks, blocks, 1);
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, zero);
        IntBuffer r = read(rec, blocks * blocks);
        int badPixels = 0, badBlocks = 0, unrecorded = 0, recordedButLost = 0;
        Map<String, Integer> lostByCu = new TreeMap<>();
        List<String> examples = new ArrayList<>();
        for (int by = 0; by < blocks; by++) {
            for (int bx = 0; bx < blocks; bx++) {
                int bad = 0;
                for (int y = by * 8; y < by * 8 + 8; y++)
                    for (int x = bx * 8; x < bx * 8 + 8; x++) {
                        int o = (y * size + x) * 4;
                        if ((zero.get(o) & 255) != (x & 255) || (zero.get(o + 1) & 255) != (y & 255)
                                || (zero.get(o + 2) & 255) != ((x ^ y) & 255) || (zero.get(o + 3) & 255) != 255) bad++;
                    }
                int record = r.get(by * blocks + bx);
                if (record >= 0) unrecorded++;
                if (bad == 0) continue;
                badPixels += bad;
                badBlocks++;
                if (record < 0) { recordedButLost++; lostByCu.merge(cu(record), 1, Integer::sum); }
                if (examples.size() < 6) examples.add("(" + bx * 8 + "," + by * 8 + "):" + bad + "@" + (record < 0 ? cu(record) : "none"));
            }
        }
        boolean clean = badPixels == 0 && unrecorded == 0;
        if (!clean) failures++;
        say("image 1024x1024 rgba8 run=%d pixels_bad=%d blocks_bad=%d/%d unrecorded=%d recorded_but_lost=%d %s",
                run, badPixels, badBlocks, blocks * blocks, unrecorded, recordedButLost, clean ? "clean" : "LOSS");
        if (!clean) {
            say("image run=%d examples((x,y):bad@se.sh.cu)=%s", run, examples);
            say("image run=%d lost_by_cu=%s", run, lostByCu);
        }
        MemoryUtil.memFree(zero); MemoryUtil.memFree(r);
        glDeleteBuffers(rec);
        glDeleteTextures(texture);
        glDeleteProgram(program);
    }

    // ------------------------------------------------------------------ radeonsi's own blits (uploads, copies, clears)

    private static final int SIZE = 1024;

    /** Reads a texture through an application compute shader (proven clean), never through a driver blit. */
    private static final String FETCH_SHADER = """
            #version 430 core
            layout(local_size_x=8, local_size_y=8) in;
            layout(binding=0) uniform sampler2D tex;
            layout(std430,binding=0) buffer Out { uint px[]; };
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                px[p.y * 1024 + p.x] = packUnorm4x8(texelFetch(tex, p, 0));
            }
            """;

    private static int fetchProgram;

    private static int expected(int x, int y, int salt) {
        return (x & 255) | ((y & 255) << 8) | (((x ^ y ^ salt) & 255) << 16) | (255 << 24);
    }

    private static ByteBuffer pattern(int width, int height, int x0, int y0, int salt) {
        ByteBuffer data = MemoryUtil.memAlloc(width * height * 4);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) data.putInt(expected(x0 + x, y0 + y, salt));
        return data.flip();
    }

    private static int texture() {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, SIZE, SIZE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return texture;
    }

    private static void verify(String name, int texture, int salt) { verify(name, texture, salt, false, 0); }

    /** Compares a texture with expected(x, y, salt), or with one constant value. */
    private static void verify(String name, int texture, int salt, boolean constant, int value) {
        if (fetchProgram == 0) fetchProgram = compute(FETCH_SHADER);
        int out = buffer(0, (long) SIZE * SIZE * 4);
        glUseProgram(fetchProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(glGetUniformLocation(fetchProgram, "tex"), 0);
        glDispatchCompute(SIZE / 8, SIZE / 8, 1);
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        IntBuffer px = read(out, SIZE * SIZE);
        int badPixels = 0, badBlocks = 0;
        List<String> examples = new ArrayList<>();
        Map<Integer, Integer> blockSizes = new TreeMap<>();
        boolean[] seen = new boolean[(SIZE / 8) * (SIZE / 8)];
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++) {
                int want = constant ? value : expected(x, y, salt);
                if (px.get(y * SIZE + x) == want) continue;
                badPixels++;
                int block = (y / 8) * (SIZE / 8) + x / 8;
                if (!seen[block]) {
                    seen[block] = true;
                    badBlocks++;
                    if (examples.size() < 8) examples.add("(" + x + "," + y + ")=0x" + Integer.toHexString(px.get(y * SIZE + x)));
                }
            }
        // How the bad pixels cluster: count 64x64 squares that are entirely wrong.
        int fullSquares = 0;
        for (int sy = 0; sy < SIZE; sy += 64)
            for (int sx = 0; sx < SIZE; sx += 64) {
                boolean all = true;
                for (int y = sy; y < sy + 64 && all; y++)
                    for (int x = sx; x < sx + 64 && all; x++)
                        if (px.get(y * SIZE + x) == (constant ? value : expected(x, y, salt))) all = false;
                if (all) fullSquares++;
            }
        boolean clean = badPixels == 0;
        if (!clean) failures++;
        say("%s pixels_bad=%d blocks8_bad=%d/%d squares64_fully_bad=%d/%d %s", name, badPixels, badBlocks,
                (SIZE / 8) * (SIZE / 8), fullSquares, (SIZE / 64) * (SIZE / 64), clean ? "clean" : "LOSS");
        if (!clean) say("%s examples=%s", name, examples);
        MemoryUtil.memFree(px);
        glDeleteBuffers(out);
    }

    private static void internal(int run) {
        int salt = 17 + run;
        // Whole-texture upload: the block atlas path.
        int a = texture();
        ByteBuffer whole = pattern(SIZE, SIZE, 0, 0, salt);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, whole);
        MemoryUtil.memFree(whole);
        verify("upload whole run=" + run, a, salt);

        // Many small sub-uploads, as an atlas is stitched.
        int b = texture();
        for (int y = 0; y < SIZE; y += 16)
            for (int x = 0; x < SIZE; x += 16) {
                ByteBuffer tile = pattern(16, 16, x, y, salt);
                glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, 16, 16, GL_RGBA, GL_UNSIGNED_BYTE, tile);
                MemoryUtil.memFree(tile);
            }
        verify("upload 16x16 tiles run=" + run, b, salt);

        // Texture to texture copy.
        int c = texture();
        glCopyImageSubData(a, GL_TEXTURE_2D, 0, 0, 0, 0, c, GL_TEXTURE_2D, 0, 0, 0, 0, SIZE, SIZE, 1);
        verify("copy image run=" + run, c, salt);

        // Clear.
        int clearValue = 0xff3366cc;
        ByteBuffer color = MemoryUtil.memAlloc(4).putInt(0, clearValue);
        glClearTexImage(c, 0, GL_RGBA, GL_UNSIGNED_BYTE, color);
        MemoryUtil.memFree(color);
        verify("clear image run=" + run, c, 0, true, clearValue);

        // Graphics reading compute-written data in the same command stream: draw the upload into a framebuffer.
        int d = texture();
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, d, 0);
        int draw = drawProgram();
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        glViewport(0, 0, SIZE, SIZE);
        glUseProgram(draw);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, a);
        glUniform1i(glGetUniformLocation(draw, "tex"), 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        verify("draw sampling upload run=" + run, d, salt);

        glDeleteVertexArrays(vao);
        glDeleteProgram(draw);
        glDeleteFramebuffers(fbo);
        glDeleteTextures(new int[]{a, b, c, d});
    }

    // ------------------------------------------------------------------ radeonsi's buffer copies and clears

    /** Copies a storage buffer into CPU-visible memory with an application compute shader, never a driver copy. */
    private static final String GATHER_SHADER = """
            #version 430 core
            layout(local_size_x=256) in;
            layout(std430,binding=0) readonly buffer Src { uint src[]; };
            layout(std430,binding=1) writeonly buffer Dst { uint dst[]; };
            uniform uint count;
            void main() { uint i = gl_GlobalInvocationID.x; if (i < count) dst[i] = src[i]; }
            """;

    private static int gatherProgram;

    private static IntBuffer gather(int buffer, int words) {
        if (gatherProgram == 0) gatherProgram = compute(GATHER_SHADER);
        int visible = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, visible);
        glBufferStorage(GL_SHADER_STORAGE_BUFFER, (long) words * 4, GL_MAP_READ_BIT | GL_CLIENT_STORAGE_BIT);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, visible);
        glUseProgram(gatherProgram);
        glUniform1ui(glGetUniformLocation(gatherProgram, "count"), words);
        glDispatchCompute((words + 255) / 256, 1, 1);
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        glFinish();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, visible);
        ByteBuffer mapped = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, (long) words * 4, GL_MAP_READ_BIT);
        if (mapped == null) throw new IllegalStateException("could not map the readback buffer");
        IntBuffer copy = MemoryUtil.memAllocInt(words);
        copy.put(mapped.asIntBuffer()).flip();
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
        glDeleteBuffers(visible);
        return copy;
    }

    /** A GPU-only storage buffer (no CPU access flags, so the driver keeps it in video memory). */
    private static int gpuBuffer(int words, IntBuffer contents) {
        int buffer = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, buffer);
        glBufferStorage(GL_COPY_WRITE_BUFFER, (long) words * 4, GL_DYNAMIC_STORAGE_BIT);
        if (contents != null) glBufferSubData(GL_COPY_WRITE_BUFFER, 0, contents);
        return buffer;
    }

    private static void compare(String name, IntBuffer got, int[] want) {
        int bad = 0, firstBad = -1, runs = 0;
        boolean inRun = false;
        for (int i = 0; i < want.length; i++) {
            boolean wrong = got.get(i) != want[i];
            if (wrong) { bad++; if (firstBad < 0) firstBad = i; if (!inRun) runs++; }
            inRun = wrong;
        }
        boolean clean = bad == 0;
        if (!clean) failures++;
        say("%s words_bad=%d/%d bad_runs=%d first_bad=%d %s", name, bad, want.length, runs, firstBad, clean ? "clean" : "LOSS");
    }

    private static void bufferOps(int run) {
        final int words = 4 << 20;   // 16 MiB
        int[] source = new int[words];
        for (int i = 0; i < words; i++) source[i] = i * 0x9E3779B1 ^ (run * 7919);
        IntBuffer upload = MemoryUtil.memAllocInt(words).put(source).flip();
        int src = gpuBuffer(words, upload);
        MemoryUtil.memFree(upload);

        // One large copy.
        int dst = gpuBuffer(words, null);
        glBindBuffer(GL_COPY_READ_BUFFER, src);
        glBindBuffer(GL_COPY_WRITE_BUFFER, dst);
        glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, (long) words * 4);
        IntBuffer got = gather(dst, words);
        compare("buffer copy 16MiB run=" + run, got, source);
        MemoryUtil.memFree(got);

        // Many small copies at odd offsets and sizes, as chunk geometry is moved.
        int[] want = new int[words];
        // Defined starting contents (zeros from the CPU): storage without data is undefined.
        IntBuffer zeros = MemoryUtil.memCallocInt(words);
        int moved = gpuBuffer(words, zeros);
        MemoryUtil.memFree(zeros);
        glBindBuffer(GL_COPY_READ_BUFFER, src);
        glBindBuffer(GL_COPY_WRITE_BUFFER, moved);
        java.util.Random random = new java.util.Random(run);
        for (int n = 0; n < 2000; n++) {
            int length = 1 + random.nextInt(4096), from = random.nextInt(words - length), to = random.nextInt(words - length);
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, (long) from * 4, (long) to * 4, (long) length * 4);
            System.arraycopy(source, from, want, to, length);
        }
        got = gather(moved, words);
        compare("buffer copies 2000 small run=" + run, got, want);
        MemoryUtil.memFree(got);

        // Clears, aligned and of odd lengths.
        int[] cleared = source.clone();
        glBindBuffer(GL_COPY_WRITE_BUFFER, dst);
        IntBuffer value = MemoryUtil.memAllocInt(1);
        for (int n = 0; n < 200; n++) {
            int length = 1 + random.nextInt(65536), at = random.nextInt(words - length), v = random.nextInt();
            value.put(0, v);
            glClearBufferSubData(GL_COPY_WRITE_BUFFER, GL_R32UI, (long) at * 4, (long) length * 4, GL_RED_INTEGER, GL_UNSIGNED_INT, value);
            java.util.Arrays.fill(cleared, at, at + length, v);
        }
        MemoryUtil.memFree(value);
        got = gather(dst, words);
        compare("buffer clears 200 run=" + run, got, cleared);
        MemoryUtil.memFree(got);

        // Sub-data updates into a GPU buffer (staged through a driver copy).
        int[] updated = cleared.clone();
        for (int n = 0; n < 500; n++) {
            int length = 1 + random.nextInt(8192), at = random.nextInt(words - length);
            IntBuffer part = MemoryUtil.memAllocInt(length);
            for (int i = 0; i < length; i++) { int v = random.nextInt(); part.put(i, v); updated[at + i] = v; }
            glBufferSubData(GL_COPY_WRITE_BUFFER, (long) at * 4, part);
            MemoryUtil.memFree(part);
        }
        got = gather(dst, words);
        compare("buffer updates 500 run=" + run, got, updated);
        MemoryUtil.memFree(got);

        glDeleteBuffers(new int[]{src, dst, moved});
    }

    // ------------------------------------------------------------------ the colour block reading GPU-written data

    private static final String QUAD_VS = "#version 430 core\nuniform vec4 rect; void main(){ vec2 c = vec2((gl_VertexID & 1), (gl_VertexID >> 1) & 1);"
            + " gl_Position = vec4(mix(rect.xy, rect.zw, c), 0.0, 1.0); }\n";
    private static final String COLOR_FS = "#version 430 core\nuniform vec4 color; out vec4 o; void main(){ o = color; }\n";

    private static int colorProgram;

    private static int program(String vs, String fs) {
        int v = glCreateShader(GL_VERTEX_SHADER), f = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(v, vs); glShaderSource(f, fs);
        glCompileShader(v); glCompileShader(f);
        int p = glCreateProgram();
        glAttachShader(p, v); glAttachShader(p, f); glLinkProgram(p);
        glDeleteShader(v); glDeleteShader(f);
        if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(p));
        return p;
    }

    /** A quad in normalised device coordinates. */
    private static void quad(float x0, float y0, float x1, float y1, int rgba) {
        if (colorProgram == 0) colorProgram = program(QUAD_VS, COLOR_FS);
        glUseProgram(colorProgram);
        glUniform4f(glGetUniformLocation(colorProgram, "rect"), x0, y0, x1, y1);
        glUniform4f(glGetUniformLocation(colorProgram, "color"), (rgba & 255) / 255f, ((rgba >>> 8) & 255) / 255f,
                ((rgba >>> 16) & 255) / 255f, ((rgba >>> 24) & 255) / 255f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    private static void colorBlock(int run) {
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        glViewport(0, 0, SIZE, SIZE);
        int fbo = glGenFramebuffers();

        // 1. Fast clear (CMASK cleared by the driver), then draw over the left half, as a loading screen does,
        //    at several sizes: A = four frames in one batch, C = one clear and draw on a fresh texture.
        int lastClear = 0xff20e020;
        int[][] sizes = {{1920, 1080}, {1280, 720}, {1024, 1024}, {512, 512}, {1024, 512}, {2048, 1024}};
        for (int[] wh : sizes) {
            for (int variant = 0; variant < 2; variant++) {
                int w = wh[0], h = wh[1];
                int target = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, target);
                glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
                glViewport(0, 0, w, h);
                for (int frame = variant == 1 ? 3 : 0; frame < 4; frame++) {
                    int clear = frame % 2 == 0 ? 0xff2020e0 : 0xff20e020;
                    glClearColor((clear & 255) / 255f, ((clear >>> 8) & 255) / 255f, ((clear >>> 16) & 255) / 255f, 1);
                    glClear(GL_COLOR_BUFFER_BIT);
                    quad(-1, -1, 0, 1, 0xffffffff);
                }
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glMemoryBarrier(GL_ALL_BARRIER_BITS);
                verifySized("fast clear " + w + "x" + h + (variant == 0 ? " A frames" : " C single") + " run=" + run,
                        target, w, h, 0xffffffff, lastClear);
                glDeleteTextures(target);
            }
        }
        glViewport(0, 0, SIZE, SIZE);

        // 2. Compute writes a texture, then a draw blends onto it (the colour block reads compute's result).
        int written = texture();
        glBindImageTexture(0, written, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        int fill = compute("""
                #version 430 core
                layout(local_size_x=8, local_size_y=8) in;
                layout(rgba8, binding=0) uniform writeonly image2D img;
                void main() { imageStore(img, ivec2(gl_GlobalInvocationID.xy), vec4(0.25, 0.5, 0.0, 1.0)); }
                """);
        glUseProgram(fill);
        glDispatchCompute(SIZE / 8, SIZE / 8, 1);
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, written, 0);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        quad(-1, -1, 1, 1, 0xff400040);
        glDisable(GL_BLEND);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        // 0.25+0.25=0.5 -> 128, 0.5+0 -> 128, 0+0.25 -> 64, alpha 1
        int sum = 0xff000000 | (64 << 16) | (128 << 8) | 128;
        verifyNear("compute then blend run=" + run, written, sum);

        glDeleteProgram(fill);
        glDeleteFramebuffers(fbo);
        glDeleteTextures(written);
        glDeleteVertexArrays(vao);
    }

    private static final String SIZED_FETCH_SHADER = """
            #version 430 core
            layout(local_size_x=8, local_size_y=8) in;
            layout(binding=0) uniform sampler2D tex;
            layout(std430,binding=0) buffer Out { uint px[]; };
            uniform ivec2 size;
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x < size.x && p.y < size.y) px[p.y * size.x + p.x] = packUnorm4x8(texelFetch(tex, p, 0));
            }
            """;

    private static int sizedFetchProgram;

    /** Left half of a w x h texture one colour, right half another, read by an application compute shader. */
    private static void verifySized(String name, int texture, int w, int h, int left, int right) {
        if (sizedFetchProgram == 0) sizedFetchProgram = compute(SIZED_FETCH_SHADER);
        int out = buffer(0, (long) w * h * 4);
        glUseProgram(sizedFetchProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(glGetUniformLocation(sizedFetchProgram, "tex"), 0);
        glUniform2i(glGetUniformLocation(sizedFetchProgram, "size"), w, h);
        glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        IntBuffer px = read(out, w * h);
        glDeleteBuffers(out);
        int bad = 0, leftBad = 0, rightBad = 0;
        java.util.Set<Integer> blocks = new java.util.HashSet<>();
        Map<String, Integer> values = new TreeMap<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int want = x < w / 2 ? left : right, got = px.get(y * w + x);
                if (got == want) continue;
                bad++;
                if (x < w / 2) leftBad++; else rightBad++;
                blocks.add((y / 8) * ((w + 7) / 8) + x / 8);
                if (values.size() < 4) values.merge(Integer.toHexString(got), 1, Integer::sum);
            }
        if (bad != 0) failures++;
        say("%s pixels_bad=%d (left %d, right %d) of %d blocks8_bad=%d values=%s %s", name, bad, leftBad, rightBad,
                w * h, blocks.size(), values, bad == 0 ? "clean" : "LOSS");
        MemoryUtil.memFree(px);
    }

    private static IntBuffer fetch(int texture) {
        if (fetchProgram == 0) fetchProgram = compute(FETCH_SHADER);
        int out = buffer(0, (long) SIZE * SIZE * 4);
        glUseProgram(fetchProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(glGetUniformLocation(fetchProgram, "tex"), 0);
        glDispatchCompute(SIZE / 8, SIZE / 8, 1);
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        IntBuffer px = read(out, SIZE * SIZE);
        glDeleteBuffers(out);
        return px;
    }

    /** Left half one colour, right half another; reports wrong pixels and 8x8 blocks. */
    private static void verifyHalves(String name, int texture, int left, int right) {
        IntBuffer px = fetch(texture);
        int bad = 0, badBlocks = 0;
        java.util.Set<Integer> blocks = new java.util.HashSet<>();
        Map<String, Integer> values = new TreeMap<>();
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++) {
                int want = x < SIZE / 2 ? left : right, got = px.get(y * SIZE + x);
                if (got == want) continue;
                bad++;
                if (blocks.add((y / 8) * (SIZE / 8) + x / 8)) badBlocks++;
                if (values.size() < 6) values.merge(Integer.toHexString(got), 1, Integer::sum);
            }
        if (bad != 0) failures++;
        say("%s pixels_bad=%d blocks8_bad=%d values=%s %s", name, bad, badBlocks, values, bad == 0 ? "clean" : "LOSS");
        if (bad != 0) {
            // Where the bad 8x8 blocks are: rows 0..3 of blocks across the left half (1 = bad).
            for (int by = 0; by < 4; by++) {
                StringBuilder row = new StringBuilder();
                for (int bx = 0; bx < SIZE / 16; bx++) row.append(blocks.contains(by * (SIZE / 8) + bx) ? '1' : '0');
                say("%s block_row %d: %s", name, by, row);
            }
            StringBuilder col = new StringBuilder();
            for (int by = 0; by < SIZE / 8; by += 8) col.append(blocks.contains(by * (SIZE / 8)) ? '1' : '0');
            say("%s block_col0 every 8th row: %s", name, col);
        }
        MemoryUtil.memFree(px);
    }

    private static void verifyNear(String name, int texture, int want) {
        IntBuffer px = fetch(texture);
        int bad = 0;
        Map<String, Integer> values = new TreeMap<>();
        for (int i = 0; i < SIZE * SIZE; i++) {
            int got = px.get(i);
            boolean near = true;
            for (int shift = 0; shift < 32; shift += 8)
                if (Math.abs(((got >>> shift) & 255) - ((want >>> shift) & 255)) > 2) near = false;
            if (near) continue;
            bad++;
            if (values.size() < 6) values.merge(Integer.toHexString(got), 1, Integer::sum);
        }
        if (bad != 0) failures++;
        say("%s pixels_bad=%d/%d values=%s %s", name, bad, SIZE * SIZE, values, bad == 0 ? "clean" : "LOSS");
        MemoryUtil.memFree(px);
    }

    private static int drawProgram() {
        int vs = glCreateShader(GL_VERTEX_SHADER), fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(vs, "#version 430 core\nvoid main(){ vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);"
                + " gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }\n");
        glShaderSource(fs, "#version 430 core\nlayout(binding=0) uniform sampler2D tex; out vec4 color;\n"
                + "void main(){ color = texelFetch(tex, ivec2(gl_FragCoord.xy), 0); }\n");
        glCompileShader(vs);
        glCompileShader(fs);
        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
        return program;
    }

    // ------------------------------------------------------------------ helpers

    private static String cu(int record) {
        return ((record >>> 13) & 3) + "." + ((record >>> 12) & 1) + "." + ((record >>> 8) & 15);
    }

    private static int compute(String source) {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) throw new IllegalStateException(glGetShaderInfoLog(shader));
        int program = glCreateProgram();
        glAttachShader(program, shader);
        glLinkProgram(program);
        glDeleteShader(shader);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
        return program;
    }

    /** A zero-filled storage buffer bound at the index; zeros come from the CPU, not a GPU clear. */
    private static int buffer(int binding, long bytes) {
        int buffer = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        ByteBuffer zero = MemoryUtil.memCalloc((int) bytes);
        glBufferData(GL_SHADER_STORAGE_BUFFER, zero, GL_DYNAMIC_READ);
        MemoryUtil.memFree(zero);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, buffer);
        return buffer;
    }

    private static IntBuffer read(int buffer, int ints) {
        IntBuffer data = MemoryUtil.memAllocInt(ints);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, data);
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException("GL error 0x" + Integer.toHexString(error));
        return data;
    }

    private static void say(String format, Object... values) {
        System.out.println("CT " + String.format(Locale.ROOT, format, values));
    }
}
