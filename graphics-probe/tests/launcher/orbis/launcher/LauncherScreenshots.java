package orbis.launcher;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

/**
 * Renders the real launcher screen with OpenGL at 1920x1080 and saves each page as a PNG, so the look can be
 * reviewed without a console: tests/launcher_screenshots.py runs it under Xvfb.
 */
final class LauncherScreenshots {
    private LauncherScreenshots() { }

    public static void main(String[] args) throws Exception {
        Path out = Files.createDirectories(Path.of(args[0]));
        Path root = Files.createTempDirectory("launcher-screens");
        Path runner = Files.createDirectories(root.resolve("runner"));
        Path game = Files.createDirectories(root.resolve("game/mods"));
        Files.writeString(runner.resolve("jvm-options.txt"), "-Xmx1024m\n-Dorbis.graphics.arenaMib=1536\n");
        Files.writeString(runner.resolve("runner.conf"), "java_home=" + root.resolve("jdk") + "\ndebug=false\n");
        Path metadata = Files.writeString(root.resolve("version.json"),
                "{\"id\": \"fabric-loader-0.14.9.5-1.18.2\", \"mainClass\": \"net.fabricmc.loader.impl.launch.knot.KnotClient\"}");
        for (String mod : List.of("sodium", "lithium", "modmenu", "iris.jar.disabled"))
            Files.write(game.resolve(mod.endsWith(".disabled") ? mod : mod + ".jar"), new byte[]{1});
        System.setProperty("orbis.launcher.home", root.resolve("home").toString());
        System.setProperty("orbis.runner.root", runner.toString());
        System.setProperty("orbis.launcher.gameMetadata", metadata.toString());
        System.setProperty("user.dir", root.resolve("game").toString());
        LauncherConfig config = LauncherConfig.load();
        new AccountStore(config.home, config.runnerRoot).putMicrosoft(
                "0123456789abcdef0123456789abcdef", "Steve", "00000000-0000-0000-0000-000000000000", "refresh");

        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long window = glfwCreateWindow(1920, 1080, "screens", 0, 0);
        if (window == 0) throw new IllegalStateException("window creation failed");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        Renderer renderer = new Renderer();
        renderer.setUp();
        Random random = new Random(1);
        LauncherLogicTest.FakePacks packs = new LauncherLogicTest.FakePacks(random);
        LauncherLogicTest.FakeModrinth modrinth = new LauncherLogicTest.FakeModrinth(random, packs);
        LauncherScreen screen = new LauncherScreen(config, renderer, modrinth,
                new LauncherLogicTest.FakeInstaller(modrinth, game, random));

        save(screen, out.resolve("1-instances.png"));
        // From the instance's actions (Play, Mods, Instance settings, New instance, ...).
        shoot(screen, out, "2-mods", Input.Action.FOCUS, Input.Action.DOWN, Input.Action.CONFIRM);
        screen.apply("", List.of(Input.Action.BACK));
        shoot(screen, out, "3-instance-settings", Input.Action.FOCUS, Input.Action.DOWN, Input.Action.CONFIRM,
                Input.Action.DOWN, Input.Action.DOWN);
        screen.apply("", List.of(Input.Action.BACK));
        shoot(screen, out, "4-new-instance", Input.Action.FOCUS, Input.Action.DOWN, Input.Action.CONFIRM);
        screen.apply("", List.of(Input.Action.BACK));
        shoot(screen, out, "5-accounts", Input.Action.NEXT_SECTION);
        // The same page after keyboard input: the hints name keys instead of buttons.
        screen.applyEvents("", List.of(new Input.Event(Input.Source.KEYBOARD, Input.Action.DOWN)));
        settle(screen);
        save(screen, out.resolve("6-accounts-keyboard.png"));
        glfwDestroyWindow(window);
        glfwTerminate();
        System.out.println("screenshots in " + out);
    }

    private static void shoot(LauncherScreen screen, Path out, String name, Input.Action... actions) throws Exception {
        screen.applyEvents("", java.util.Arrays.stream(actions)
                .map(action -> new Input.Event(Input.Source.CONTROLLER, action)).toList());
        settle(screen);
        save(screen, out.resolve(name + ".png"));
    }

    private static void settle(LauncherScreen screen) throws InterruptedException {
        for (int i = 0; i < 50 && screen.applyResults(); i++) Thread.sleep(20);
    }

    private static void save(LauncherScreen screen, Path file) throws Exception {
        screen.frame(1920, 1080);
        glFinish();
        ByteBuffer pixels = MemoryUtil.memAlloc(1920 * 1080 * 4);
        glReadPixels(0, 0, 1920, 1080, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 1080; y++)
            for (int x = 0; x < 1920; x++) {
                int i = ((1079 - y) * 1920 + x) * 4;
                image.setRGB(x, y, (pixels.get(i) & 255) << 16 | (pixels.get(i + 1) & 255) << 8 | pixels.get(i + 2) & 255);
            }
        MemoryUtil.memFree(pixels);
        javax.imageio.ImageIO.write(image, "png", file.toFile());
        Files.writeString(file.resolveSibling("README.txt"), "Rendered by LauncherScreenshots\n", StandardCharsets.UTF_8);
    }
}
