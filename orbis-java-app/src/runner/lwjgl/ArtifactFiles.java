package runner.lwjgl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Confined path resolution and hashing shared by metadata and bundle validation. */
final class ArtifactFiles {
    private ArtifactFiles() {}

    /**
     * Resolves a metadata-relative path below {@code realRoot}, rejecting absolute paths,
     * dot segments, separators other than '/', and symlinks that leave the root.
     */
    static Path confined(Path realRoot, String relative, String where,
            LaunchPlanException.Category missing) throws LaunchPlanException {
        if (relative.isEmpty() || relative.startsWith("/") || relative.indexOf('\\') >= 0
                || relative.indexOf(':') >= 0 || relative.indexOf('\0') >= 0)
            throw new LaunchPlanException(LaunchPlanException.Category.ARTIFACT_PATH,
                    where + " path is not a plain relative path: " + relative);
        for (String segment : relative.split("/", -1))
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new LaunchPlanException(LaunchPlanException.Category.ARTIFACT_PATH,
                        where + " path has an empty or dot segment: " + relative);
        Path candidate = realRoot.resolve(relative).normalize();
        if (!candidate.startsWith(realRoot))
            throw new LaunchPlanException(LaunchPlanException.Category.ARTIFACT_PATH,
                    where + " path escapes " + realRoot + ": " + relative);
        if (!Files.isRegularFile(candidate))
            throw new LaunchPlanException(missing, where + " is missing: " + candidate);
        try {
            if (!candidate.toRealPath().startsWith(realRoot))
                throw new LaunchPlanException(LaunchPlanException.Category.ARTIFACT_PATH,
                        where + " resolves outside " + realRoot + ": " + relative);
        } catch (IOException failure) {
            throw new LaunchPlanException(missing, where + " cannot be resolved: " + failure.getMessage());
        }
        return candidate;
    }

    /** Returns lowercase hex digests for each algorithm, reading the file once. */
    static String[] digests(Path file, String... algorithms) throws IOException {
        MessageDigest[] digests = new MessageDigest[algorithms.length];
        try {
            for (int i = 0; i < algorithms.length; ++i) digests[i] = MessageDigest.getInstance(algorithms[i]);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            for (int read; (read = input.read(buffer)) > 0; )
                for (MessageDigest digest : digests) digest.update(buffer, 0, read);
        }
        String[] result = new String[digests.length];
        for (int i = 0; i < digests.length; ++i) result[i] = HexFormat.of().formatHex(digests[i].digest());
        return result;
    }
}
