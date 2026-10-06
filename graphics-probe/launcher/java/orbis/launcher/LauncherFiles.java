package orbis.launcher;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Small bounded persistence operations shared by launch journals and account records. */
final class LauncherFiles {
    private LauncherFiles() { }

    static void rejectSymlinks(Path path) throws IOException {
        for (Path part = path.toAbsolutePath().normalize(); part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("A launcher storage path is symlinked");
    }

    static byte[] read(Path path, long maxBytes) throws IOException {
        rejectSymlinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maxBytes)
            throw new IOException("Missing, invalid or oversized launcher file: " + path.getFileName());
        try (java.io.InputStream in = Files.newInputStream(path);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            long total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) throw new IOException("Launcher file exceeds its size limit");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    static void write(Path path, byte[] data, boolean privateFile) throws IOException {
        rejectSymlinks(path);
        Path parent = path.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        Path temporary;
        try {
            temporary = privateFile ? Files.createTempFile(parent, ".launcher-", ".new",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(parent, ".launcher-", ".new");
        } catch (UnsupportedOperationException unsupported) {
            temporary = Files.createTempFile(parent, ".launcher-", ".new");
        }
        try {
            // createTempFile makes owner-only files. Ordinary ones (runner.conf, jvm-options, records) must stay
            // readable to FTP and the user, like the console's other files; only credentials stay private.
            if (!privateFile) {
                try { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-rw-rw-")); }
                catch (UnsupportedOperationException | IOException ignored) { /* Best effort. */ }
            }
            Files.write(temporary, data);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
