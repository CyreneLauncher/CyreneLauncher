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

    public static boolean vulkanScanoutReady;
    public static boolean nVulkanScanoutReady() { return vulkanScanoutReady; }
    public static int vulkanWindows, vulkanSurfaceResult;
    public static long vulkanInstance, vulkanAllocator;
    public static long nCreateVulkanWindow() { vulkanWindows++; return 1; }
    public static int nVulkanCreateSurface(long instance, long allocator, long output) {
        vulkanInstance = instance; vulkanAllocator = allocator;
        if (vulkanSurfaceResult == 0) org.lwjgl.system.MemoryUtil.memPutLong(output, 0x9876);
        return vulkanSurfaceResult;
    }
    public static boolean nVulkanPresentationSupport(long instance, long physical, int family) { return family == 0; }

    public static boolean nMakeCurrent(long window) { current = window; return true; }

    public static boolean nSwapBuffers(long window) { swaps++; return window == 1; }

    public static boolean swapIntervalSuccess = true;
    public static boolean nSwapInterval(int interval) { return swapIntervalSuccess; }

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

    public static byte[] systemStatus = new byte[16];
    public static int statusCalls;
    public static int nSystemStatus(byte[] out) { statusCalls++; System.arraycopy(systemStatus, 0, out, 0, Math.min(out.length, systemStatus.length)); return 0; }

    public static int relaunches;
    public static int nRelaunch() { relaunches++; return -1; }
    public static int nRelaunchWithIntent(String preparationId) { relaunches++; return -1; }

    // System keyboard: never opens on the host.
    public static int nImeBegin(String initial, String title, int maxLength) { return -1; }
    public static int imeBeginResult = -1, imeStatus = 0;
    public static boolean imeMultiline;
    public static int imeLabel;
    public static int nImeBeginOptions(String initial, String title, int maxLength, boolean multiline, int enterLabel) {
        imeMultiline = multiline; imeLabel = enterLabel; return imeBeginResult;
    }
    public static int nImePoll() { return imeStatus; }
    public static String nImeResult() { return ""; }
    public static int nImeCancel() { return 0; }
    public static int browserOpenResult = -1, browserStatus = 0;
    public static String browserUrl;
    public static int nBrowserOpen(String url) { browserUrl = url; return browserOpenResult; }
    public static int nBrowserPoll() { return browserStatus; }
}
