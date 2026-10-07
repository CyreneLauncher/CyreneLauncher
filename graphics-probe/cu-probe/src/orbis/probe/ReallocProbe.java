package orbis.probe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Locale;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;

/**
 * Checks realloc of large blocks in two modules that each link their own musl: libjvm (Unsafe.reallocateMemory)
 * and liblwjgl (MemoryUtil.nmemRealloc). Blocks above musl's mmap threshold (~224 KiB) are their own mappings;
 * shrinking one used to copy the OLD size past the new block, because Orbis has no mremap. The runtime's
 * __mremap now shrinks in place, so a shrunk block must keep its address and its contents, and small blocks
 * allocated around it must stay intact. Lines start with RP for the log; the screen ends green or red.
 */
public final class ReallocProbe {
    private static final int ROUNDS = 64;
    private static final int GUARDS = 32;
    private static final long GUARD_SIZE = 4096;
    private static int failures;

    private interface Heap {
        long malloc(long size) throws Throwable;
        long realloc(long address, long size) throws Throwable;
        void free(long address) throws Throwable;
    }

    private ReallocProbe() {}

    public static void main(String[] args) throws Throwable {
        Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> type = unsafe.getClass();
        MethodHandle allocate = lookup.findVirtual(type, "allocateMemory",
                MethodType.methodType(long.class, long.class)).bindTo(unsafe);
        MethodHandle reallocate = lookup.findVirtual(type, "reallocateMemory",
                MethodType.methodType(long.class, long.class, long.class)).bindTo(unsafe);
        MethodHandle release = lookup.findVirtual(type, "freeMemory",
                MethodType.methodType(void.class, long.class)).bindTo(unsafe);

        say("lwjgl allocator=%s", MemoryUtil.getAllocator().getClass().getName());
        check("libjvm", new Heap() {
            public long malloc(long size) throws Throwable { return (long) allocate.invokeExact(size); }
            public long realloc(long address, long size) throws Throwable {
                return (long) reallocate.invokeExact(address, size);
            }
            public void free(long address) throws Throwable { release.invokeExact(address); }
        });
        check("liblwjgl", new Heap() {
            public long malloc(long size) { return MemoryUtil.nmemAllocChecked(size); }
            public long realloc(long address, long size) { return MemoryUtil.nmemRealloc(address, size); }
            public void free(long address) { MemoryUtil.nmemFree(address); }
        });
        say("summary failures=%d %s", failures, failures == 0 ? "result=clean" : "result=LOSS");
        show(failures == 0);
        System.exit(0);
    }

    // Each round: small guard blocks around a 1 MiB block, shrink it to 300 KiB and 240 KiB (both still mapped,
    // so both go through __mremap), grow it to 2 MiB (musl's copy, correct for growth), and check everything.
    private static void check(String module, Heap heap) throws Throwable {
        int moved = 0, bad = 0;
        long[] guards = new long[GUARDS];
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < GUARDS / 2; i++) guards[i] = filled(heap.malloc(GUARD_SIZE), GUARD_SIZE, i);
            long block = filled(heap.malloc(1 << 20), 1 << 20, 1000 + round);
            for (int i = GUARDS / 2; i < GUARDS; i++) guards[i] = filled(heap.malloc(GUARD_SIZE), GUARD_SIZE, i);

            for (long size : new long[] {300 << 10, 240 << 10}) {
                long shrunk = heap.realloc(block, size);
                if (shrunk != block) moved++;
                block = shrunk;
                if (!intact(block, size, 1000 + round)) bad++;
            }
            block = heap.realloc(block, 2 << 20);
            if (block == 0 || !intact(block, 240 << 10, 1000 + round)) bad++;
            for (int i = 0; i < GUARDS; i++) {
                if (!intact(guards[i], GUARD_SIZE, i)) bad++;
                heap.free(guards[i]);
            }
            heap.free(block);
        }
        // Shrinks that moved mean the old copy path still runs in this module.
        boolean ok = moved == 0 && bad == 0;
        if (!ok) failures++;
        say("%s shrinks=%d moved=%d damaged=%d %s", module, ROUNDS * 2, moved, bad, ok ? "clean" : "LOSS");
    }

    private static long filled(long address, long size, int seed) {
        if (address == 0) throw new OutOfMemoryError("malloc " + size);
        for (long offset = 0; offset < size; offset += 8) MemoryUtil.memPutLong(address + offset, word(seed, offset));
        return address;
    }

    private static boolean intact(long address, long size, int seed) {
        if (address == 0) return false;
        for (long offset = 0; offset < size; offset += 8)
            if (MemoryUtil.memGetLong(address + offset) != word(seed, offset)) return false;
        return true;
    }

    private static long word(int seed, long offset) {
        return (seed * 0x9e3779b97f4a7c15L) ^ (offset * 0xbf58476d1ce4e5b9L);
    }

    private static void show(boolean clean) {
        if (!glfwInit()) return;
        try {
            long window = glfwCreateWindow(640, 480, "Orbis realloc probe", 0, 0);
            if (window == 0) return;
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            glClearColor(clean ? 0.05f : 0.6f, clean ? 0.4f : 0.05f, 0.05f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glfwSwapBuffers(window);
            glFinish();
            Thread.sleep(3000);
            GL.setCapabilities(null);
            glfwDestroyWindow(window);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            glfwTerminate();
        }
    }

    private static void say(String format, Object... values) {
        System.out.println("RP " + String.format(Locale.ROOT, format, values));
    }
}
