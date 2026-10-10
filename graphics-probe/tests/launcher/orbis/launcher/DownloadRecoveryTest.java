package orbis.launcher;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class DownloadRecoveryTest {
    private DownloadRecoveryTest() { }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root);
        byte[] data = "a verified download that survives interruption".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path target = root.resolve("game.jar"); String sha = hash(data);
        DownloadCache.Source broken = (url, offset) -> new DownloadCache.Response(new InputStream() {
            int index;
            @Override public int read() throws IOException {
                if (index == 12) throw new IOException("connection dropped");
                return data[index++] & 255;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (index == 12) throw new IOException("connection dropped");
                int n = Math.min(len, 12 - index); System.arraycopy(data, index, b, off, n); index += n; return n;
            }
        }, 200, 0, data.length, data.length);
        reject(() -> DownloadCache.get(target, "https://test/game", "SHA-256", sha, data.length, 1024, broken, (n,t) -> { }));
        check(!Files.exists(target), "interrupted bytes are never installed");
        DownloadCache.get(target, "https://test/game", "SHA-256", sha, data.length, 1024, (url, offset) -> {
            check(offset == 12, "retry requests only missing bytes");
            return new DownloadCache.Response(new ByteArrayInputStream(data, 12, data.length - 12), 206, offset, data.length, data.length - 12);
        }, (n,t) -> { });
        check(java.util.Arrays.equals(Files.readAllBytes(target), data), "resumed file matches original");
        DownloadCache.get(target, "https://test/game", "SHA-256", sha, data.length, 1024, (u,o) -> { throw new AssertionError("valid cache should avoid network"); }, (n,t) -> { });
        Files.delete(target);
        reject(() -> DownloadCache.get(target, "https://test/game", "SHA-256", sha, data.length, 1024, broken, (n,t) -> { }));
        DownloadCache.get(target, "https://test/game", "SHA-256", sha, data.length, 1024, (u,o) -> {
            check(o == 12, "partial retained");
            return new DownloadCache.Response(new ByteArrayInputStream(data), 200, 0, data.length, data.length);
        }, (n,t) -> { });
        check(java.util.Arrays.equals(Files.readAllBytes(target), data), "servers ignoring Range restart safely");
        Files.delete(target);
        reject(() -> DownloadCache.get(target, "https://test/game", "SHA-256", sha, data.length, 1024, broken, (n,t) -> { }));
        byte[] updated = "updated rolling release".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DownloadCache.get(target, "https://test/game", "SHA-256", hash(updated), updated.length, 1024, (u,o) -> {
            check(o == 0, "changed checksum discards stale partial");
            return new DownloadCache.Response(new ByteArrayInputStream(updated), 200, 0, updated.length, updated.length);
        }, (n,t) -> { });
        Files.delete(target);
        reject(() -> DownloadCache.get(target, "https://test/game", "SHA-256", sha, data.length, 1024, (u,o) ->
                new DownloadCache.Response(new ByteArrayInputStream(new byte[data.length]), 200, 0, data.length, data.length), (n,t) -> { }));
        check(!Files.exists(target), "bad checksum is rejected");
        reject(() -> DownloadCache.get(target, "https://test/game", "SHA-256", sha, data.length, 1024, broken, (n,t) -> { }));
        reject(() -> DownloadCache.get(target, "https://test/game", "SHA-256", sha, data.length, 1024, (u,o) ->
                new DownloadCache.Response(new ByteArrayInputStream(data), 206, 0, data.length, data.length), (n,t) -> { }));
        check(!Files.exists(target), "wrong Content-Range cannot be installed");
        httpRanges(root.resolve("http.jar"), data, sha);
        Path symlink = root.resolve("link"); Files.createSymbolicLink(symlink, root);
        reject(() -> DownloadCache.get(symlink.resolve("bad.jar"), "https://test/game", "SHA-256", sha, data.length, 1024, broken, (n,t) -> { }));
        System.out.println("Download recovery passed");
    }
    private static void httpRanges(Path target, byte[] data, String sha) throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> range = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/file", exchange -> {
            int number = requests.incrementAndGet();
            range.set(exchange.getRequestHeaders().getFirst("Range"));
            check("identity".equals(exchange.getRequestHeaders().getFirst("Accept-Encoding")), "resume disables compression");
            if (number == 1) {
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data, 0, 12);
            } else {
                exchange.getResponseHeaders().set("Content-Range", "bytes 12-" + (data.length - 1) + "/" + data.length);
                exchange.sendResponseHeaders(206, data.length - 12);
                exchange.getResponseBody().write(data, 12, data.length - 12);
            }
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            DownloadCache.Source source = (u,o) -> DownloadCache.connection((java.net.HttpURLConnection) java.net.URI.create(u).toURL().openConnection(), o);
            reject(() -> DownloadCache.get(target, url, "SHA-256", sha, data.length, 1024, source, (n,t) -> { }));
            DownloadCache.get(target, url, "SHA-256", sha, data.length, 1024, source, (n,t) -> { });
            check("bytes=12-".equals(range.get()) && requests.get() == 2, "real HTTP retry sends Range for preserved bytes");
            check(java.util.Arrays.equals(Files.readAllBytes(target), data), "real HTTP resumed data verified");
            reject(() -> DownloadCache.http(url, 0));
        } finally { server.stop(0); }
    }

    @FunctionalInterface interface Action { void run() throws Exception; }
    static void reject(Action a) throws Exception { try { a.run(); } catch (IOException expected) { return; } throw new AssertionError("must reject"); }
    static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
