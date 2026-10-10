package orbis.launcher;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persistent, checksum-bound partial transfers; only verified files become installable. */
final class DownloadCache {
    interface Source { Response open(String url, long offset) throws IOException; }
    interface Progress { void report(long downloaded, long total); }
    record Response(InputStream in, int status, long start, long total, long length) { }
    private static final Object[] LOCKS = new Object[64];
    static { for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object(); }
    private DownloadCache() { }

    static Path root() { return Path.of(System.getProperty("orbis.launcher.home", "/data/orbis-jdk/launcher")).resolve("cache/downloads"); }

    static byte[] bytes(Path cache, String url, String algorithm, String hash, long size, long max) throws IOException {
        String key = digestBytes((url + "\n" + algorithm + "\n" + hash).getBytes(StandardCharsets.UTF_8));
        Path target = cache.resolve(key);
        get(target, url, algorithm, hash, size, max, DownloadCache::http, (n,t) -> { });
        return LauncherFiles.read(target, max);
    }

    static void get(Path target, String url, String algorithm, String hash, long size, long max,
                    Source source, Progress progress) throws IOException {
        target = target.toAbsolutePath().normalize();
        synchronized (LOCKS[(target.hashCode() & Integer.MAX_VALUE) % LOCKS.length]) {
            transfer(target, url, algorithm, hash, size, max, source, progress);
        }
    }

    private static void transfer(Path target, String url, String algorithm, String hash, long size, long max,
                                 Source source, Progress progress) throws IOException {
        if (max <= 0 || size < -1 || size > max || hash == null || !hash.matches("[a-f0-9]+")
                || hash.length() != digest(algorithm).getDigestLength() * 2) throw new IOException("Invalid download checksum or size");
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Path identity = target.resolveSibling(target.getFileName() + ".part-id");
        LauncherFiles.rejectSymlinks(target); LauncherFiles.rejectSymlinks(part); LauncherFiles.rejectSymlinks(identity);
        Files.createDirectories(target.getParent());
        if (valid(target, algorithm, hash, size, max)) return;
        String key = digestBytes((url + "\n" + algorithm + "\n" + hash + "\n" + size + "\n" + max).getBytes(StandardCharsets.UTF_8));
        boolean same = Files.isRegularFile(identity, LinkOption.NOFOLLOW_LINKS)
                && new String(LauncherFiles.read(identity, 128), StandardCharsets.US_ASCII).equals(key);
        if (!same || !Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS) || Files.size(part) > max
                || size >= 0 && Files.size(part) > size) Files.deleteIfExists(part);
        LauncherFiles.write(identity, key.getBytes(StandardCharsets.US_ASCII), false);
        if (valid(part, algorithm, hash, size, max)) { promote(part, target, identity); return; }
        long offset = Files.exists(part) ? Files.size(part) : 0;
        if (size >= 0 && offset == size) { Files.deleteIfExists(part); offset = 0; }
        Response reply = source.open(url, offset);
        try (InputStream in = reply.in()) {
            if (reply.status() != 200 && reply.status() != 206) {
                if (reply.status() == 416) Files.deleteIfExists(part);
                throw new IOException("Download returned HTTP " + reply.status() + "; retry to restart");
            }
            if (reply.status() == 200) offset = 0;
            else if (reply.start() != offset || reply.total() < offset || reply.length() != reply.total() - offset) {
                Files.deleteIfExists(part);
                throw new IOException("Download server returned an invalid resume range; retry to restart");
            }
            long total = reply.total();
            if (total > max || size >= 0 && total >= 0 && size != total) {
                Files.deleteIfExists(part); throw new IOException("Download size differs from the published size");
            }
            long received = offset;
            progress.report(received, size >= 0 ? size : total);
            try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    offset == 0 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) != -1; ) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException("Download cancelled");
                    received += n;
                    if (received > max || size >= 0 && received > size || total >= 0 && received > total) {
                        Files.deleteIfExists(part); throw new IOException("Download exceeds its size limit");
                    }
                    out.write(buffer, 0, n);
                    progress.report(received, size >= 0 ? size : total);
                }
            }
            if (size >= 0 && received < size || total >= 0 && received < total)
                throw new IOException("Download interrupted; partial data saved. Retry to resume.");
        }
        if (!valid(part, algorithm, hash, size, max)) {
            Files.deleteIfExists(part); Files.deleteIfExists(identity);
            throw new IOException("Download checksum does not match; retry to download again");
        }
        promote(part, target, identity);
    }

    private static void promote(Path part, Path target, Path identity) throws IOException {
        try { Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(part, target, StandardCopyOption.REPLACE_EXISTING); }
        Files.deleteIfExists(identity);
    }

    private static boolean valid(Path file, String algorithm, String expected, long size, long max) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > max
                || size >= 0 && Files.size(file) != size) return false;
        MessageDigest checksum = digest(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Download cancelled");
                checksum.update(buffer, 0, n);
            }
        }
        return expected.equals(HexFormat.of().formatHex(checksum.digest()));
    }
    private static MessageDigest digest(String algorithm) throws IOException {
        try { return MessageDigest.getInstance(algorithm); }
        catch (NoSuchAlgorithmException invalid) { throw new IOException("Unsupported download checksum", invalid); }
    }
    private static String digestBytes(byte[] bytes) throws IOException { return HexFormat.of().formatHex(digest("SHA-256").digest(bytes)); }

    static Response http(String url, long offset) throws IOException {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme())) throw new IOException("Downloads require HTTPS");
        return connection((HttpURLConnection) uri.toURL().openConnection(), offset);
    }

    /** Connection seam lets host tests exercise real range headers on a loopback server. */
    static Response connection(HttpURLConnection connection, long offset) throws IOException {
        String protocol = connection.getURL().getProtocol();
        connection.setConnectTimeout(15000); connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "CyreneLauncher/PS4");
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
        try {
            int status = connection.getResponseCode();
            long length = connection.getContentLengthLong(), start = 0, total = length;
            if (!protocol.equals(connection.getURL().getProtocol())) throw new IOException("Download redirected away from HTTPS");
            if (status == 206) {
                Matcher range = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(String.valueOf(connection.getHeaderField("Content-Range")));
                if (!range.matches()) throw new IOException("Download server returned an invalid Content-Range");
                try {
                    start = Long.parseLong(range.group(1)); total = Long.parseLong(range.group(3));
                    long end = Long.parseLong(range.group(2));
                    if (end != total - 1 || start > end || length >= 0 && length != end - start + 1)
                        throw new IOException("Download server returned an incomplete range");
                    length = end - start + 1;
                } catch (NumberFormatException invalid) { throw new IOException("Invalid download range", invalid); }
            }
            if (status == 416) { connection.disconnect(); return new Response(InputStream.nullInputStream(), status, 0, -1, 0); }
            if (status != 200 && status != 206) throw new IOException("Download returned HTTP " + status);
            InputStream stream = new FilterInputStream(connection.getInputStream()) {
                @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } }
            };
            return new Response(stream, status, start, total, length);
        } catch (IOException | RuntimeException failed) { connection.disconnect(); throw failed; }
    }
}
