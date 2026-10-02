package runner.lwjgl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static runner.lwjgl.LaunchPlanException.Category.METADATA_PARSE;
import static runner.lwjgl.TestSupport.check;
import static runner.lwjgl.TestSupport.equal;
import static runner.lwjgl.TestSupport.fails;

public final class JsonReaderTest {
    public static void main(String[] args) throws Exception {
        Object parsed = parse(" {\"a\": [1, -0, 2.5e3, true, false, null, {}, []],"
                + " \"s\": \"q\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\\ud83d\\ude00\", \"n\": {\"k\": \"v\"}} ");
        Map<?, ?> object = (Map<?, ?>) parsed;
        equal(List.of("a", "s", "n"), List.copyOf(object.keySet()), "insertion order kept");
        List<?> array = (List<?>) object.get("a");
        equal(new BigDecimal("1"), array.get(0), "integer");
        equal(new BigDecimal("-0"), array.get(1), "negative zero");
        equal(new BigDecimal("2.5e3"), array.get(2), "exponent");
        equal(Boolean.TRUE, array.get(3), "true");
        equal(Boolean.FALSE, array.get(4), "false");
        check(array.get(5) == JsonReader.NULL, "null sentinel");
        equal(Map.of(), array.get(6), "empty object");
        equal(List.of(), array.get(7), "empty array");
        equal("q\"\\/\b\f\n\r\t\u00e9\ud83d\ude00", object.get("s"), "escapes");
        equal(Map.of("k", "v"), object.get("n"), "nested object");
        check(!object.containsKey("missing"), "absent key differs from null");
        try {
            ((Map<String, Object>) object).put("x", 1);
            throw new AssertionError("objects must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }

        String[] malformed = {"", "{", "[1,]", "{\"a\":1,}", "01", "1.", "-", "1e", "\"\\x\"",
                "tru", "nul", "{\"a\":1}x", "\"a\nb\"", "{\"a\":1,\"a\":2}", "{'a':1}", "[1 2]",
                "{\"a\" 1}", "\"\\u12\"", "\"unterminated", "+1", ".5", "NaN"};
        for (String text : malformed) fails(METADATA_PARSE, "malformed " + text, () -> parse(text));
        fails(METADATA_PARSE, "invalid UTF-8", () -> JsonReader.parse(new byte[]{'"', (byte) 0xc3, '"'}, 8));
        fails(METADATA_PARSE, "number length", () -> parse("1" + "0".repeat(70)));

        equal(1, ((List<?>) JsonReader.parse(nested(64).getBytes(StandardCharsets.UTF_8), 64)).size(),
                "depth 64 accepted");
        fails(METADATA_PARSE, "depth 65", () -> JsonReader.parse(nested(65).getBytes(StandardCharsets.UTF_8), 64));
        fails(METADATA_PARSE, "deep object", () -> JsonReader.parse("{\"a\":{\"b\":{}}}".getBytes(StandardCharsets.UTF_8), 2));

        Path directory = Files.createTempDirectory("json-reader");
        Path file = TestSupport.write(directory.resolve("big.json"), "[\"0123456789\"]");
        equal(List.of("0123456789"), JsonReader.parse(file, 14, 4), "exact size limit accepted");
        fails(METADATA_PARSE, "oversized input", () -> JsonReader.parse(file, 13, 4));
        fails(METADATA_PARSE, "missing file", () -> JsonReader.parse(directory.resolve("absent.json")));
        System.out.println("JsonReader fixtures passed");
    }

    private static Object parse(String text) throws LaunchPlanException {
        return JsonReader.parse(text.getBytes(StandardCharsets.UTF_8), JsonReader.DEFAULT_MAX_DEPTH);
    }

    private static String nested(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }
}
