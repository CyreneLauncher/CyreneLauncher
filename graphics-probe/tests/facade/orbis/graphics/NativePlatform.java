package orbis.graphics;

import java.util.ArrayList;
import java.util.List;

/** Host stand-in for the platform PRX: records calls and returns canned results. */
public final class NativePlatform {
    public static int initializeCalls, terminateCalls, swaps, destroyed;
    public static int[] lastContext;
    public static long current;
    public static final List<String> procNames = new ArrayList<>();
    public static int width = 1920, height = 1080;

    private NativePlatform() {}

    public static String mesaLogFile;
    public static int nInitialize(String processName, int arenaMib, String mesaLogFile) {
        initializeCalls++; NativePlatform.mesaLogFile = mesaLogFile; return 0;
    }

    public static long nCreateWindow(int glMajor, int glMinor, boolean coreProfile,
            boolean forwardCompatible, int depthBits, int stencilBits) {
        lastContext = new int[]{glMajor, glMinor, coreProfile ? 1 : 0, forwardCompatible ? 1 : 0, depthBits, stencilBits};
        return 1;
    }

    public static boolean nMakeCurrent(long window) { current = window; return true; }

    public static boolean nSwapBuffers(long window) { swaps++; return window == 1; }

    public static boolean nSwapInterval(int interval) { return true; }

    public static long nGetProcAddress(String function) { procNames.add(function); return 0x1000 + function.length(); }

    public static int nFramebufferWidth(long window) { return width; }

    public static int nFramebufferHeight(long window) { return height; }

    public static void nDestroyWindow(long window) { destroyed++; }

    public static void nTerminate() { terminateCalls++; }

    public static String nLastError() { return "none"; }

    public static int pointerDraws;
    public static void nDrawPointer(int x, int top, int scale) { pointerDraws++; }

    public static int[] padState = new int[8];
    public static int nPadRead(int[] out) { System.arraycopy(padState, 0, out, 0, 8); return 0; }

    public static int relaunches;
    public static int nRelaunch() { relaunches++; return -1; }
}
