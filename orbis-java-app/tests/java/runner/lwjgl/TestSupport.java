package runner.lwjgl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Minimal assertion helpers for the standalone fixture programs. */
final class TestSupport {
    private TestSupport() {}

    interface Action {
        void run() throws Exception;
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
    }

    static LaunchPlanException fails(LaunchPlanException.Category category, String name, Action action)
            throws Exception {
        try {
            action.run();
        } catch (LaunchPlanException failure) {
            if (failure.category() != category)
                throw new AssertionError(name + ": expected " + category + " but got " + failure.getMessage(), failure);
            return failure;
        }
        throw new AssertionError(name + ": expected " + category + " failure");
    }

    static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    static String hash(String algorithm, byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }

    static String hash(String algorithm, Path file) throws Exception {
        return hash(algorithm, Files.readAllBytes(file));
    }
}
