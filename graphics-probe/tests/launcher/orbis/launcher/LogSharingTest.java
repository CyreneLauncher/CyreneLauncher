package orbis.launcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
public final class LogSharingTest {
    private LogSharingTest() { }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root);
        Path log = root.resolve("crash.txt");
        Files.writeString(log, "java.lang.OutOfMemoryError: native allocation failed\n--accessToken secret-access --username Player\n{\"refresh_token\":\"secret-refresh\"}\nAuthorization: Bearer secret-bearer\nemail user@example.com ip 192.168.1.4\n[CHAT] private conversation\nat example.Mod.tick(Mod.java:12)\n", StandardCharsets.UTF_8);
        Files.writeString(log, "\n{\"refresh_token\":\n\"multiline-secret\"}\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        String report = LogSharing.prepare(log);
        for (String secret : new String[]{"secret-access", "secret-refresh", "secret-bearer", "user@example.com", "192.168.1.4", "private conversation", "multiline-secret"})
            check(!report.contains(secret), "redact " + secret);
        check(report.contains("OutOfMemoryError") && report.contains("Mod.java:12"), "preserve diagnostic details");
        String url = LogSharing.upload(report, content -> {
            check(content.equals(report), "upload exactly the reviewed report");
            return "{\"success\":true,\"url\":\"https://mclo.gs/abc123\",\"token\":\"delete-token\"}";
        });
        check(url.equals("https://mclo.gs/abc123"), "returns shareable URL");
        reject(() -> LogSharing.upload(report, c -> "{\"success\":false,\"error\":\"service unavailable\"}"));
        reject(() -> LogSharing.upload(report, c -> "{\"success\":true,\"url\":\"http://evil.test/x\"}"));
        Files.writeString(log, "x".repeat(LogSharing.MAX_BYTES + 100) + "\nfinal crash detail\n");
        String bounded = LogSharing.prepare(log);
        check(bounded.getBytes(StandardCharsets.UTF_8).length <= LogSharing.MAX_BYTES && bounded.contains("final crash detail"), "bounded report retains log tail");
        Path link = root.resolve("linked.log"); Files.createSymbolicLink(link, log);
        reject(() -> LogSharing.prepare(link));
        String longLine = "z".repeat(100000) + "end-of-report";
        java.util.List<String> rows = LogSharing.preview(longLine, 80, c -> 1);
        check(String.join("", rows).equals(longLine) && rows.stream().allMatch(row -> row.length() <= 80), "full preview includes long-line suffixes in bounded rows");
        System.out.println("Log sharing passed");
    }
    @FunctionalInterface interface Action { void run() throws Exception; }
    static void reject(Action a) throws Exception { try { a.run(); } catch (java.io.IOException expected) { return; } throw new AssertionError("must reject"); }
    static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
