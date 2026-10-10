package orbis.launcher;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.tukaani.xz.XZInputStream;

/**
 * The Java runtimes the package does not carry, downloaded when a Minecraft version first needs one, as
 * AngelAuraMC's launcher does. They come from orbis-openjdk's rolling download release, are checked against
 * its SHA256SUMS, and are replaced when that release has moved on. Offline, an installed runtime is kept.
 */
final class RuntimeInstaller {
    static final String RELEASE = "https://github.com/CyreneLauncher/orbis-openjdk/releases/download/download/";
    /** The Java versions the release provides beyond the packaged JDK 17. */
    static final int[] DOWNLOADABLE = {21, 25};
    /** Records the archive's SHA-256 inside an installed runtime. */
    static final String MARKER = ".release-sha256";
    private static final String USER_AGENT = "orbis-launcher (PlayStation 4 Minecraft launcher)";
    private static final long MAX_ARCHIVE = 512L << 20, MAX_UNPACKED = 1536L << 20;

    /** The network seam, replaced in tests: a stream and its length in bytes (negative when unknown). */
    interface Source { Opened open(String url) throws IOException; }
    record Opened(InputStream in, long length) { }
    /** What is happening, and how much of it is done (0 to 1, negative when not known). */
    interface Progress { void report(String text, double fraction); }

    private final Path root;
    private final Source source;

    RuntimeInstaller(Path root) {
        this(root, RuntimeInstaller::download);
    }

    RuntimeInstaller(Path root, Source source) {
        this.root = root;
        this.source = source;
    }

    static String asset(int feature) {
        return "jre" + feature + "-orbis-x86_64.tar.xz";
    }

    /** The release Java version that serves required: the oldest one new enough, or -1. */
    static int downloadable(int required) {
        for (int feature : DOWNLOADABLE)
            if (feature >= required) return feature;
        return -1;
    }

    /**
     * Makes sure root/jdk<feature> holds the download release's runtime: installs it when missing, replaces it
     * when the release has a different build. An installed runtime survives a failed check.
     */
    Path ensure(int feature, Progress progress) throws IOException {
        Path home = root.resolve("jdk" + feature);
        String installed = installedSha(home);
        String expected;
        try {
            expected = expectedSha(feature);
        } catch (IOException offline) {
            if (installed != null) {
                System.err.println("launcher: cannot check for a newer Java " + feature + ": " + offline.getMessage());
                makeLoadable(home);
                return home;
            }
            throw new IOException("Java " + feature + " must be downloaded first: " + offline.getMessage(), offline);
        }
        if (expected.equals(installed)) {
            // Launchers before this one unpacked modules without execute permission.
            makeLoadable(home);
            return home;
        }
        progress.report((installed == null ? "Downloading" : "Updating") + " Java " + feature + "...", 0);
        install(feature, expected, progress);
        return home;
    }

    private static String installedSha(Path home) {
        Path marker = home.resolve(MARKER);
        if (Runtimes.feature(home, -1) < 0 || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            return new String(LauncherFiles.read(marker, 128), StandardCharsets.US_ASCII).trim();
        } catch (IOException unreadable) {
            return null;
        }
    }

    private String expectedSha(int feature) throws IOException {
        String name = asset(feature);
        String sums;
        try (InputStream in = source.open(RELEASE + "SHA256SUMS").in()) {
            sums = new String(in.readNBytes(65536), StandardCharsets.US_ASCII);
        }
        for (String line : sums.split("\n")) {
            String[] parts = line.trim().split("\\s+\\*?");
            if (parts.length == 2 && parts[1].equals(name) && parts[0].matches("[0-9a-f]{64}")) return parts[0];
        }
        throw new IOException("The runtime release lists no " + name);
    }

    private void install(int feature, String sha256, Progress progress) throws IOException {
        Files.createDirectories(root);
        String name = "jdk" + feature;
        Path archive = root.resolve("." + name + ".tar.xz"), staging = root.resolve("." + name + "-staging"),
                old = root.resolve("." + name + "-old");
        deleteTree(staging);
        deleteTree(old);
        try {
            MessageDigest digest = sha256();
            Opened opened = source.open(RELEASE + asset(feature));
            try (InputStream in = new DigestInputStream(opened.in(), digest);
                 OutputStream out = Files.newOutputStream(archive)) {
                byte[] buffer = new byte[1 << 16];
                long total = 0, shown = 0;
                for (int n; (n = in.read(buffer)) > 0; ) {
                    total += n;
                    if (total > MAX_ARCHIVE) throw new IOException("Java " + feature + " download is too large");
                    out.write(buffer, 0, n);
                    if (total - shown >= 1 << 20) {
                        shown = total;
                        progress.report("Downloading Java " + feature + " (" + (total >> 20)
                                + (opened.length() > 0 ? " of " + (opened.length() >> 20) : "") + " MiB)...",
                                opened.length() > 0 ? (double) total / opened.length() : -1);
                    }
                }
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(sha256))
                throw new IOException("Java " + feature + " download does not match its release checksum");
            progress.report("Unpacking Java " + feature + "...", -1);
            try (InputStream in = new XZInputStream(new BufferedInputStream(Files.newInputStream(archive), 1 << 16))) {
                Tar.extract(in, staging, name + "/", MAX_UNPACKED);
            }
            Path home = staging.resolve(name);
            if (Runtimes.feature(home, -1) != feature)
                throw new IOException("Java " + feature + " download holds no Java " + feature + " runtime");
            makeLoadable(home);
            Files.writeString(home.resolve(MARKER), sha256 + "\n", StandardCharsets.US_ASCII);
            Path target = root.resolve(name);
            // The launcher runs on the packaged JDK 17, so the runtime being replaced is not in use.
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.move(target, old, StandardCopyOption.ATOMIC_MOVE);
            Files.move(home, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(archive);
            deleteTree(staging);
            deleteTree(old);
        }
    }

    /**
     * Marks a runtime's native modules executable. The kernel loads a PRX only when execute access is granted
     * (VOP_ACCESS, EACCES 0xd otherwise); the release archive records them 0644 and the package installer, not
     * the archive, gives the packaged JDK its modes.
     */
    static void makeLoadable(Path home) throws IOException {
        Path lib = home.resolve("lib");
        if (!Files.isDirectory(lib, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isExecutable(lib.resolve("server/libjvm.prx")) && Files.isExecutable(lib.resolve("libjava.so"))) return;
        try (java.util.stream.Stream<Path> files = Files.walk(lib)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                boolean module = name.endsWith(".prx") || name.endsWith(".sprx") || name.endsWith(".so");
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) || module && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    if (!file.toFile().setExecutable(true, false) || !file.toFile().setReadable(true, false))
                        throw new IOException("Cannot make " + file + " loadable");
            }
        }
    }

    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) deleteTree(child);
            }
        }
        Files.delete(path);
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Opened download(String url) throws IOException {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme())) throw new IOException("Refusing non-HTTPS download " + url);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        int status = connection.getResponseCode();
        if (status != 200) {
            connection.disconnect();
            throw new IOException("HTTP " + status + " from " + uri.getHost());
        }
        return new Opened(connection.getInputStream(), connection.getContentLengthLong());
    }

    /** The subset of tar the release archives use: directories and regular files, with PAX or GNU long names. */
    static final class Tar {
        private Tar() { }

        static void extract(InputStream in, Path target, String prefix, long limit) throws IOException {
            Files.createDirectories(target);
            Path base = target.toAbsolutePath().normalize();
            byte[] header = new byte[512];
            String longName = null;
            long total = 0;
            while (true) {
                if (in.readNBytes(header, 0, 512) != 512) throw new IOException("Truncated runtime archive");
                if (zero(header)) return;
                long size = octal(header, 124, 12);
                char type = (char) header[156];
                if (type == 'x' || type == 'L') {
                    byte[] data = readEntry(in, size);
                    longName = type == 'L' ? cString(data, 0, data.length) : paxPath(data);
                    continue;
                }
                if (type == 'g') { readEntry(in, size); continue; }
                String name = longName != null ? longName : entryName(header);
                longName = null;
                if (!name.startsWith(prefix) && !name.equals(prefix.substring(0, prefix.length() - 1)))
                    throw new IOException("Unexpected runtime archive entry " + name);
                Path path = base.resolve(name).normalize();
                if (!path.startsWith(base) || name.contains("\\") || name.startsWith("/")
                        || java.util.Arrays.asList(name.split("/")).contains(".."))
                    throw new IOException("Unsafe runtime archive entry " + name);
                if (type == '5') {
                    Files.createDirectories(path);
                    skip(in, padding(size));
                } else if (type == '0' || type == 0) {
                    total += size;
                    if (total > limit) throw new IOException("Runtime archive unpacks too large");
                    Files.createDirectories(path.getParent());
                    try (OutputStream out = Files.newOutputStream(path)) {
                        byte[] buffer = new byte[1 << 16];
                        for (long left = size; left > 0; ) {
                            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                            if (n < 0) throw new IOException("Truncated runtime archive");
                            out.write(buffer, 0, n);
                            left -= n;
                        }
                    }
                    skip(in, padding(size) - size);
                } else throw new IOException("Unsupported runtime archive entry " + name);
            }
        }

        private static byte[] readEntry(InputStream in, long size) throws IOException {
            if (size > 1 << 20) throw new IOException("Oversized runtime archive header");
            byte[] data = in.readNBytes((int) size);
            if (data.length != size) throw new IOException("Truncated runtime archive");
            skip(in, padding(size) - size);
            return data;
        }

        private static String paxPath(byte[] data) throws IOException {
            String text = new String(data, StandardCharsets.UTF_8), path = null;
            for (int at = 0; at < text.length(); ) {
                int space = text.indexOf(' ', at);
                if (space < 0) throw new IOException("Invalid runtime archive header");
                int length = Integer.parseInt(text.substring(at, space));
                String record = text.substring(space + 1, at + length - 1);
                if (record.startsWith("path=")) path = record.substring(5);
                at += length;
            }
            return path;
        }

        private static String entryName(byte[] header) {
            String name = cString(header, 0, 100);
            String prefix = cString(header, 345, 155);
            return prefix.isEmpty() ? name : prefix + "/" + name;
        }

        private static String cString(byte[] data, int offset, int length) {
            int end = offset;
            while (end < offset + length && data[end] != 0) end++;
            return new String(data, offset, end - offset, StandardCharsets.UTF_8);
        }

        private static long octal(byte[] header, int offset, int length) throws IOException {
            String text = cString(header, offset, length).trim();
            try { return text.isEmpty() ? 0 : Long.parseLong(text, 8); }
            catch (NumberFormatException invalid) { throw new IOException("Invalid runtime archive header"); }
        }

        private static long padding(long size) {
            return (size + 511) / 512 * 512;
        }

        private static boolean zero(byte[] block) {
            for (byte b : block) if (b != 0) return false;
            return true;
        }

        private static void skip(InputStream in, long count) throws IOException {
            in.skipNBytes(count);
        }
    }
}
