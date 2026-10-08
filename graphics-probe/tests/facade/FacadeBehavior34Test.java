import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFWIMEStatusCallback;
import org.lwjgl.glfw.GLFWPreeditCallback;

import static org.lwjgl.glfw.GLFW.*;

/** GLFW calls only LWJGL 3.4 has (Minecraft 26.1): platform query and the IME composition callbacks. */
public final class FacadeBehavior34Test {
    private static final List<Integer> errors = new ArrayList<>();

    public static void main(String[] args) {
        glfwSetErrorCallback((code, description) -> errors.add(code));
        // Minecraft 26.1 asks before glfwInit, and hints X11 only when both X11 and Wayland are built in.
        check(!glfwPlatformSupported(GLFW_PLATFORM_WAYLAND) && !glfwPlatformSupported(GLFW_PLATFORM_X11)
                && glfwPlatformSupported(GLFW_PLATFORM_NULL) && errors.isEmpty(), "only the facade's own platform");
        check(!glfwPlatformSupported(0x7ffffffe) && last() == GLFW_INVALID_ENUM, "unknown platform rejected");

        check(glfwInit(), "glfwInit");
        glfwWindowHint(GLFW_SOFT_FULLSCREEN, GLFW_TRUE);
        check(errors.size() == 1, "the soft fullscreen hint is accepted");
        long window = glfwCreateWindow(854, 480, "Minecraft* 26.1", 0, 0);
        check(window != 0, "window created");
        int before = errors.size();
        int[] calls = new int[1];
        GLFWPreeditCallback none = glfwSetPreeditCallback(window, (w, n, s, b, sizes, f, c) -> calls[0]++);
        GLFWPreeditCallback chained = glfwSetPreeditCallback(window, (w, n, s, b, sizes, f, c) -> { });
        check(none == null && chained != null, "the previous preedit callback comes back as a callback object");
        chained.invoke(window, 0, 0L, 0, 0L, 0, 0);
        check(calls[0] == 1, "the returned callback reaches the lambda");
        GLFWIMEStatusCallback status = glfwSetIMEStatusCallback(window, w -> { });
        check(status == null && glfwSetIMEStatusCallback(window, null) != null, "IME status callback kept");
        glfwSetPreeditCursorRectangle(window, 10, 20, 1, 16);
        check(errors.size() == before, "IME calls accepted without errors");
        glfwSetPreeditCursorRectangle(12345, 0, 0, 0, 0);
        check(last() == GLFW_NO_WINDOW_CONTEXT || last() == GLFW_INVALID_VALUE, "foreign window rejected");
        glfwDestroyWindow(window);
        glfwTerminate();
        System.out.println("GLFW 3.4 facade behavior passed");
    }

    private static int last() {
        return errors.isEmpty() ? GLFW_NO_ERROR : errors.get(errors.size() - 1);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
