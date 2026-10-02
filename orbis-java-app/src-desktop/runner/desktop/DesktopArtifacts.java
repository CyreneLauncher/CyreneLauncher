package runner.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Bounded validation of the external desktop image and three-JAR Cacio bundle. */
public final class DesktopArtifacts {
    private static final String CACIO_COMMIT = "cdc19a131a0bc83057adbdcac67bc6fdebc32679";
    private static final String CACIO_ARCHIVE_SHA = "44787ad1bdbf5deedb5a5f2167c59befb78782be70d0a907cc418d8cde9cca50";
    private static final List<String> NATIVE_MODULES = List.of("jvm", "java", "net", "nio", "zip",
            "verify", "jimage", "management", "management_ext", "jaas", "freetype", "awt",
            "awt_headless", "fontmanager", "javajpeg", "lcms", "mlib_image");
    private static final List<String> JARS = List.of("cacio-shared.jar", "cacio-tta.jar",
            "orbis-desktop-adapter.jar");
    private static final Set<String> FONT = Set.of("lib/fonts/DejaVuSans.ttf");
    private static final List<String> OPTIONS = List.of("-Djava.awt.headless=false",
            "-Dcacio.managed.screensize=1280x720",
            "-Dorbis.awt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit",
            "-Dorbis.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment",
            "-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel",
            "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.font=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.java2d.pipe=ALL-UNNAMED",
            "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED",
            "--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
            "--add-exports=java.base/sun.security.action=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED");

    private DesktopArtifacts() { }

    public static void validate(Path javaHome, Path bundle) throws IOException {
        Path home = safeRoot(javaHome, "desktop Java home");
        Path root = safeRoot(bundle, "Cacio bundle");
        if (root.toString().contains(":")) fail("Cacio bundle path may not contain ':'");
        Map<String, Object> capability = object(readJson(home.resolve("lib/orbis-capabilities.json"), 65536), "runtime capabilities");
        if (!integer(capability.get("schema"), "capabilities.schema", 1)) fail("runtime capability schema must be 1");
        if (!strings(capability.get("capabilities"), "capabilities.capabilities").equals(List.of("software-desktop")))
            fail("runtime does not advertise exactly software-desktop");
        List<String> nativeModules = strings(capability.get("native_modules"), "capabilities.native_modules");
        if (!nativeModules.equals(NATIVE_MODULES)) fail("desktop native module inventory is stale or out of order");
        List<String> fonts = strings(capability.get("fonts"), "capabilities.fonts");
        if (!new HashSet<>(fonts).equals(FONT) || fonts.size() != FONT.size()) fail("desktop font inventory is stale");
        for (String name : List.of("release", "lib/modules", "lib/orbis-modules.list", "lib/server/libjvm.prx"))
            openRegular(home.resolve(name));
        String moduleText = readText(home.resolve("lib/orbis-modules.list"), 65536);
        List<String> actual = List.of(moduleText.split("\\n"));
        if (!actual.containsAll(List.of("java.base", "java.desktop", "jdk.localedata")))
            fail("Java module list is incomplete; it is separate from the native module inventory");
        for (String module : NATIVE_MODULES.subList(1, NATIVE_MODULES.size()))
            openRegular(home.resolve("lib/lib" + module + ".so"));
        for (String font : fonts) openRegular(resolveInside(home, font));

        Map<String, Object> manifest = object(readJson(root.resolve("build-manifest.json"), 65536), "Cacio build manifest");
        if (!integer(manifest.get("schema"), "manifest.schema", 1)) fail("Cacio manifest schema must be 1");
        if (!CACIO_COMMIT.equals(string(manifest.get("commit"), "manifest.commit")) ||
                !CACIO_ARCHIVE_SHA.equals(string(manifest.get("archive_sha256"), "manifest.archive_sha256")))
            fail("Cacio bundle source pin is stale");
        if (!integer(manifest.get("bridge_abi"), "manifest.bridge_abi", 1) ||
                !integer(manifest.get("packet_word_count"), "manifest.packet_word_count", 24))
            fail("Cacio bridge ABI/packet contract is stale");
        if (!strings(manifest.get("modules"), "manifest.modules").equals(
                List.of("cacio-shared", "cacio-tta", "orbis-desktop-adapter"))) fail("Cacio module inventory is stale");
        Map<String, Object> hashes = object(manifest.get("jar_sha256"), "manifest.jar_sha256");
        if (!hashes.keySet().equals(new HashSet<>(JARS))) fail("Cacio JAR hash inventory is missing or has extra entries");
        openRegular(root.resolve("LICENSE"));
        String optionText = readText(root.resolve("launch-options.txt"), 16384);
        String normalizedOptions = optionText.replace("\r\n", "\n");
        if (normalizedOptions.indexOf('\r') >= 0) fail("Cacio launch-options.txt has invalid line endings");
        String[] optionLines = normalizedOptions.split("\\n", -1);
        if (optionLines.length > 0 && optionLines[optionLines.length - 1].isEmpty())
            optionLines = java.util.Arrays.copyOf(optionLines, optionLines.length - 1);
        if (optionLines.length != OPTIONS.size()) fail("Cacio launch-options.txt is malformed or incomplete");
        for (int i = 0; i < OPTIONS.size(); i++) {
            if (optionLines[i].getBytes(StandardCharsets.UTF_8).length > 512) fail("Cacio launch option line exceeds 512 bytes");
            if (!OPTIONS.get(i).equals(optionLines[i])) fail("Cacio launch-options.txt has a conflicting option at line " + (i + 1));
        }
        for (String jar : JARS) {
            Path path = root.resolve("lib").resolve(jar);
            openRegular(path);
            String expected = string(hashes.get(jar), "manifest.jar_sha256." + jar);
            if (!expected.matches("[0-9a-f]{64}") || !expected.equals(sha256(path))) fail("Cacio JAR SHA256 mismatch: " + jar);
            try (JarFile ignored = new JarFile(path.toFile(), true)) { /* force central-directory open */ }
        }
    }

    private static Path safeRoot(Path path, String label) throws IOException {
        if (path == null || !path.isAbsolute() || path.toString().getBytes(StandardCharsets.UTF_8).length >= 512)
            throw new IOException(label + " must be an absolute path shorter than 512 bytes");
        Path normalized = path.normalize();
        Path cursor = normalized.getRoot();
        for (Path part : normalized) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("symbolic link in " + label + ": " + cursor);
        }
        return normalized;
    }
    private static Path resolveInside(Path root, String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || Path.of(relative).isAbsolute()) throw new IOException("path escapes desktop root: " + relative);
        return path;
    }
    private static void openRegular(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("symbolic link in desktop artifact path: " + cursor);
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("missing or non-regular desktop artifact: " + path);
        try (InputStream input = Files.newInputStream(path)) { input.readNBytes(1); }
    }
    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static Object readJson(Path path, int max) throws IOException {
        openRegular(path);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) { bytes = input.readNBytes(max + 1); }
        if (bytes.length > max) throw new IOException("JSON exceeds " + max + " bytes: " + path);
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        return new Json(text).parse();
    }
    private static String readText(Path path, int max) throws IOException {
        openRegular(path);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) { bytes = input.readNBytes(max + 1); }
        if (bytes.length > max) throw new IOException("text artifact exceeds " + max + " bytes: " + path);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    private static void fail(String message) throws IOException { throw new IOException(message); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value, String label) throws IOException {
        if (!(value instanceof Map<?, ?>)) throw new IOException(label + " must be an object");
        return (Map<String, Object>) value;
    }
    private static String string(Object value, String label) throws IOException {
        if (!(value instanceof String)) throw new IOException(label + " must be a string"); return (String)value;
    }
    private static List<String> strings(Object value, String label) throws IOException {
        if (!(value instanceof List<?> list)) throw new IOException(label + " must be an array");
        List<String> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (Object item : list) { String s = string(item, label + " entry"); if (!seen.add(s)) throw new IOException(label + " has duplicate entry: " + s); result.add(s); }
        return result;
    }
    private static boolean integer(Object value, String label, int expected) throws IOException {
        if (!(value instanceof Long)) throw new IOException(label + " must be an integer"); return ((Long)value) == expected;
    }

    /** Strict, bounded JSON parser: duplicate keys, malformed UTF-8 replacements, and trailing data fail. */
    private static final class Json {
        private final String text; private int at; private int depth;
        Json(String text) { this.text = text; }
        Object parse() throws IOException { Object value = value(); ws(); if (at != text.length()) bad("trailing data"); return value; }
        private Object value() throws IOException {
            ws(); if (at == text.length()) return bad("unexpected end"); char c = text.charAt(at);
            if (c == '{' || c == '[') {
                if (++depth > 64) return bad("nesting exceeds 64 levels");
                try { return c == '{' ? object() : array(); } finally { depth--; }
            }
            if (c == '"') return string();
            if (c == 't') return literal("true", Boolean.TRUE); if (c == 'f') return literal("false", Boolean.FALSE);
            if (c == 'n') return literal("null", null); if (c == '-' || c >= '0' && c <= '9') return number();
            return bad("invalid value");
        }
        private Map<String,Object> object() throws IOException {
            at++; ws(); Map<String,Object> map = new HashMap<>(); if (take('}')) return map;
            do { ws(); if (at == text.length() || text.charAt(at) != '"') return bad("object key expected"); String key=string(); ws(); need(':'); Object v=value(); if (map.containsKey(key)) return bad("duplicate key " + key); map.put(key,v); ws(); if (take('}')) return map; need(','); } while (true);
        }
        private List<Object> array() throws IOException {
            at++; ws(); List<Object> list=new ArrayList<>(); if (take(']')) return list;
            do { list.add(value()); ws(); if (take(']')) return list; need(','); } while (true);
        }
        private String string() throws IOException {
            need('"'); StringBuilder out=new StringBuilder();
            while (at<text.length()) { char c=text.charAt(at++); if (c=='"') return out.toString(); if (c<0x20) return bad("control in string");
                if (c=='\\') { if (at==text.length()) return bad("unfinished escape"); char e=text.charAt(at++); switch(e) {
                    case '"': case '\\': case '/': out.append(e); break; case 'b': out.append('\b'); break; case 'f': out.append('\f'); break; case 'n': out.append('\n'); break; case 'r': out.append('\r'); break; case 't': out.append('\t'); break;
                    case 'u':
                        if (at + 4 > text.length()) return bad("short unicode escape");
                        for (int i = at; i < at + 4; i++) if (!asciiHex(text.charAt(i))) return bad("invalid unicode escape");
                        out.append((char)Integer.parseInt(text.substring(at, at + 4), 16)); at += 4; break;
                    default:return bad("invalid escape"); }
                } else out.append(c);
            } return bad("unterminated string");
        }
        private Long number() throws IOException { int start=at; if(take('-') && at==text.length()) return bad("invalid number"); if(take('0')) { if(at<text.length() && asciiDigit(text.charAt(at))) return bad("leading zero"); } else { if(at==text.length() || text.charAt(at)<'1'||text.charAt(at)>'9') return bad("invalid number"); while(at<text.length()&&asciiDigit(text.charAt(at)))at++; } if(at<text.length() && ".eE".indexOf(text.charAt(at))>=0)return bad("non-integer number"); try{return Long.valueOf(text.substring(start,at));}catch(NumberFormatException ex){return bad("integer overflow");} }
        private boolean asciiDigit(char c) { return c >= '0' && c <= '9'; }
        private boolean asciiHex(char c) { return asciiDigit(c) || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'; }
        private Object literal(String token,Object value)throws IOException { if(!text.startsWith(token,at))return bad("invalid literal");at+=token.length();return value; }
        private void ws(){while(at<text.length()&&" \t\r\n".indexOf(text.charAt(at))>=0)at++;}
        private boolean take(char c){if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
        private void need(char c)throws IOException{if(!take(c))bad("expected '"+c+"'");}
        private <T>T bad(String why)throws IOException{throw new IOException("invalid JSON at byte/char "+at+": "+why);}
    }
}
