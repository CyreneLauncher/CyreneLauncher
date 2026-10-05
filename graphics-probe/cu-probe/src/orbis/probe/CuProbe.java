package orbis.probe;

import java.nio.IntBuffer;
import java.util.Locale;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Hardware-ID coverage probe. Requires the opt-in Orbis ACO diagnostic hook. */
public final class CuProbe {
    private CuProbe() {}
    public static void main(String[] args) {
        long window = 0;
        int program = 0, buffer = 0;
        IntBuffer data = null;
        if (!Boolean.getBoolean("orbis.graphics.cuProbe"))
            throw new IllegalStateException("CU probe requires -Dorbis.graphics.cuProbe=true");
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        try {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(640, 480, "Orbis CU coverage probe", 0, 0);
            if (window == 0) throw new IllegalStateException("glfwCreateWindow failed");
            glfwMakeContextCurrent(window);
            if (!GL.createCapabilities().GL_ARB_shader_clock)
                throw new IllegalStateException("GL_ARB_shader_clock unavailable; no hardware-ID results");
            System.out.println("CU_PROBE renderer=" + glGetString(GL_RENDERER));
            int shader = glCreateShader(GL_COMPUTE_SHADER);
            try {
                glShaderSource(shader, """
                    #version 430 core
                    #extension GL_ARB_shader_clock : require
                    layout(local_size_x=64) in;
                    layout(std430,binding=0) buffer Results { uvec4 records[]; };
                    shared uint scratch[64];
                    void main() {
                        uint gid=gl_WorkGroupID.x, lane=gl_LocalInvocationID.x;
                        uint x=gid*64u+lane+1u;
                        for(uint i=0u;i<64u;++i) x=(x^(x>>13u))*1664525u+1013904223u;
                        scratch[lane]=x;
                        barrier();
                        if(lane==0u) {
                            uint sum=0u;
                            for(uint i=0u;i<64u;++i) sum+=scratch[i];
                            records[gid]=uvec4(clock2x32ARB(),sum,gid);
                        }
                    }
                    """);
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                program = glCreateProgram();
                glAttachShader(program, shader); glLinkProgram(program);
                if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetProgramInfoLog(program));
            } finally { glDeleteShader(shader); }
            glUseProgram(program);
            final int groups = 16384;
            data = MemoryUtil.memAllocInt(groups * 4);
            buffer = glGenBuffers();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            glBufferData(GL_SHADER_STORAGE_BUFFER, (long) groups * 16, GL_DYNAMIC_READ);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer);
            int[][] hits = new int[4][32];
            long start = System.nanoTime();
            for (int round = 0; round < 12; ++round) {
                int count = round < 4 ? 4096 : groups;
                glDispatchCompute(count, 1, 1);
                glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
                data.clear(); data.limit(count * 4);
                glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, data);
                if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("compute/readback GL error");
                for (int i = 0; i < count; ++i) {
                    int raw = data.get(i * 4);
                    if (data.get(i * 4 + 1) != 0x43555052 || data.get(i * 4 + 3) != i)
                        throw new IllegalStateException("hardware-ID tag/group mismatch at " + i);
                    int se = (raw >>> 13) & 3, sh = (raw >>> 12) & 1, cu = (raw >>> 8) & 15;
                    ++hits[se][sh * 16 + cu];
                }
                int expected = 0;
                for (int lane = 0; lane < 64; ++lane) {
                    int x = lane + 1;
                    for (int j = 0; j < 64; ++j) x = (x ^ (x >>> 13)) * 1664525 + 1013904223;
                    expected += x;
                }
                if (data.get(2) != expected) throw new IllegalStateException("compute checksum mismatch");
            }
            int total = 0;
            for (int se = 0; se < 4; ++se) {
                for (int sh = 0; sh < 2; ++sh) {
                    int mask = 0, count = 0;
                    for (int cu = 0; cu < 16; ++cu) if (hits[se][sh * 16 + cu] != 0) {
                        mask |= 1 << cu; ++count;
                        System.out.printf(Locale.ROOT, "CU_PROBE se=%d sh=%d cu=%d workgroups=%d%n",
                                          se, sh, cu, hits[se][sh * 16 + cu]);
                    }
                    total += count;
                    System.out.printf(Locale.ROOT, "CU_PROBE se=%d sh=%d observed=%d mask=0x%04x%n", se, sh, count, mask);
                }
            }
            System.out.printf(Locale.ROOT, "CU_PROBE observed_total=%d seconds=%.3f result=ok%n",
                              total, (System.nanoTime() - start) / 1e9);
            System.out.println("CU_PROBE missing IDs mean not observed, not proven disabled");
            glClearColor(0.05f, 0.35f, 0.1f, 1); glClear(GL_COLOR_BUFFER_BIT);
            glfwSwapBuffers(window); glFinish();
        } finally {
            if (data != null) MemoryUtil.memFree(data);
            if (buffer != 0) glDeleteBuffers(buffer);
            if (program != 0) glDeleteProgram(program);
            if (window != 0) { GL.setCapabilities(null); glfwDestroyWindow(window); }
            glfwTerminate();
        }
    }
}
