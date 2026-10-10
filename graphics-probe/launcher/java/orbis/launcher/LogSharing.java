package orbis.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Manual, bounded log sharing: redact locally and upload only the reviewed snapshot. */
final class LogSharing {
    static final int MAX_BYTES = 1 << 20;
    interface Service { String post(String content) throws IOException; }
    private static final Pattern SENSITIVE_LINE = Pattern.compile("(?i)(access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|password|authorization|\\bbearer\\s|--username|--uuid|--xuid|user[_-]?hash|\\buhs\\b|xbl3\\.0|\\[CHAT\\])");
    private LogSharing() { }
    static String prepare(Path source) throws IOException {
        LauncherFiles.rejectSymlinks(source);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("No readable log was found");
        byte[] bytes;
        try (SeekableByteChannel in = Files.newByteChannel(source, StandardOpenOption.READ)) {
            int count = (int) Math.min(in.size(), MAX_BYTES / 4 - 4096);
            ByteBuffer buffer = ByteBuffer.allocate(count);
            in.position(Math.max(0, in.size() - count));
            while (buffer.hasRemaining() && in.read(buffer) != -1) { }
            bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (Files.size(source) > bytes.length) {
            int newline = text.indexOf('\n');
            text = "[Log truncated; recent entries follow]\n" + (newline >= 0 ? text.substring(newline + 1) : text);
        }
        text = text.replaceAll("(?is)(\"(?:access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|password|authorization)\"\\s*:\\s*\")[^\"]*(\")", "$1[removed]$2")
                .replaceAll("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}", "[token removed]");
        StringBuilder out = new StringBuilder();
        int lines = 0;
        for (String line : text.split("\\R", -1)) {
            if (++lines > 20000) { out.append("[Remaining lines omitted]\n"); break; }
            if (SENSITIVE_LINE.matcher(line).find()) line = "[Sensitive log entry removed]";
            else {
                line = line.replaceAll("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}", "[email removed]")
                        .replaceAll("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?", "[IP removed]")
                        .replaceAll("(?i)(?<![\\w:])(?:[a-f0-9]{0,4}:){2,}[a-f0-9:]{0,4}(?:%[\\w]+)?", "[IP removed]")
                        .replaceAll("(?i)(/home/|/Users/|[A-Z]:\\\\Users\\\\)[^/\\\\\\s]+", "$1[user]");
            }
            if (out.length() + line.length() + 1 > MAX_BYTES / 4) { out.append("[Remaining text omitted]\n"); break; }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    interface GlyphWidth { double width(char character); }
    /** Linear full-text wrapping: every character the player will upload stays inspectable. */
    static java.util.List<String> preview(String text, double maxWidth, GlyphWidth width) {
        java.util.List<String> rows = new java.util.ArrayList<>();
        StringBuilder row = new StringBuilder();
        double used = 0;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\n') { rows.add(row.toString()); row.setLength(0); used = 0; continue; }
            double glyph = Math.max(1, width.width(character));
            if (used + glyph > maxWidth && row.length() > 0) { rows.add(row.toString()); row.setLength(0); used = 0; }
            row.append(character); used += glyph;
        }
        if (row.length() > 0) rows.add(row.toString());
        return java.util.List.copyOf(rows);
    }

    static String upload(String reviewed) throws IOException { return upload(reviewed, LogSharing::post); }
    static String upload(String reviewed, Service service) throws IOException {
        if (reviewed.isBlank() || reviewed.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IOException("The log is empty or exceeds its sharing limit");
        try {
            JsonObject reply = JsonParser.parseString(service.post(reviewed)).getAsJsonObject();
            if (!reply.has("success") || !reply.get("success").getAsBoolean()) throw new IOException("Log service could not save the report. Try again later.");
            String url = reply.get("url").getAsString();
            if (!url.matches("https://mclo\\.gs/[A-Za-z0-9]+")) throw new IOException("Log service returned an invalid share link");
            return url;
        } catch (RuntimeException invalid) { throw new IOException("Log service returned an invalid response", invalid); }
    }

    private static String post(String content) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create("https://api.mclo.gs/1/log").toURL().openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "CyreneLauncher/PS4");
        JsonObject body = new JsonObject(); body.addProperty("content", content); body.addProperty("source", "Cyrene Launcher");
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(data.length);
        try {
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(data); }
            if (connection.getResponseCode() != 200) throw new IOException("Log service returned HTTP " + connection.getResponseCode() + ". Try again later.");
            try (InputStream in = connection.getInputStream()) {
                byte[] reply = in.readNBytes(65537);
                if (reply.length > 65536) throw new IOException("Log service returned an oversized response");
                return new String(reply, StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
