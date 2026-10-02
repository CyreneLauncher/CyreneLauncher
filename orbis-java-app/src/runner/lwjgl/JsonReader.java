package runner.lwjgl;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict, bounded RFC 8259 reader with no runtime dependencies, so metadata can be
 * parsed before any game library is loaded. Objects become unmodifiable insertion-ordered
 * maps, arrays unmodifiable lists, numbers {@link BigDecimal}, and JSON null {@link #NULL}.
 * Duplicate object keys are rejected.
 */
public final class JsonReader {
    public static final int DEFAULT_MAX_BYTES = 4 * 1024 * 1024;
    public static final int DEFAULT_MAX_DEPTH = 64;
    /** JSON null; distinct from an absent key. */
    public static final Object NULL = new Object() {
        @Override public String toString() { return "null"; }
    };

    private final String text;
    private final int maxDepth;
    private int position;

    private JsonReader(String text, int maxDepth) {
        this.text = text;
        this.maxDepth = maxDepth;
    }

    public static Object parse(Path file) throws LaunchPlanException {
        return parse(file, DEFAULT_MAX_BYTES, DEFAULT_MAX_DEPTH);
    }

    public static Object parse(Path file, int maxBytes, int maxDepth) throws LaunchPlanException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(maxBytes + 1);
        } catch (IOException failure) {
            throw new LaunchPlanException(LaunchPlanException.Category.METADATA_PARSE,
                    "cannot read " + file + ": " + failure.getMessage());
        }
        if (bytes.length > maxBytes)
            throw new LaunchPlanException(LaunchPlanException.Category.METADATA_PARSE,
                    file + " exceeds " + maxBytes + " bytes");
        return parse(bytes, maxDepth);
    }

    public static Object parse(byte[] bytes, int maxDepth) throws LaunchPlanException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new LaunchPlanException(LaunchPlanException.Category.METADATA_PARSE,
                    "metadata is not valid UTF-8");
        }
        JsonReader reader = new JsonReader(text, maxDepth);
        reader.skipWhitespace();
        Object value = reader.value(0);
        reader.skipWhitespace();
        if (reader.position != text.length()) throw reader.error("trailing data");
        return value;
    }

    private Object value(int depth) throws LaunchPlanException {
        if (position >= text.length()) throw error("unexpected end of input");
        char c = text.charAt(position);
        switch (c) {
            case '{': return object(depth + 1);
            case '[': return array(depth + 1);
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return NULL;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected character");
        }
    }

    private Map<String, Object> object(int depth) throws LaunchPlanException {
        if (depth > maxDepth) throw error("nesting deeper than " + maxDepth);
        ++position;
        Map<String, Object> members = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) return Collections.unmodifiableMap(members);
        for (;;) {
            skipWhitespace();
            if (position >= text.length() || text.charAt(position) != '"')
                throw error("expected object key");
            String key = string();
            skipWhitespace();
            if (!consume(':')) throw error("expected ':'");
            skipWhitespace();
            Object value = value(depth);
            if (members.putIfAbsent(key, value) != null) throw error("duplicate key \"" + key + "\"");
            skipWhitespace();
            if (consume('}')) return Collections.unmodifiableMap(members);
            if (!consume(',')) throw error("expected ',' or '}'");
        }
    }

    private List<Object> array(int depth) throws LaunchPlanException {
        if (depth > maxDepth) throw error("nesting deeper than " + maxDepth);
        ++position;
        List<Object> elements = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) return Collections.unmodifiableList(elements);
        for (;;) {
            skipWhitespace();
            elements.add(value(depth));
            skipWhitespace();
            if (consume(']')) return Collections.unmodifiableList(elements);
            if (!consume(',')) throw error("expected ',' or ']'");
        }
    }

    private String string() throws LaunchPlanException {
        ++position;
        StringBuilder builder = new StringBuilder();
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') return builder.toString();
            if (c < 0x20) throw error("control character in string");
            if (c != '\\') { builder.append(c); continue; }
            if (position >= text.length()) break;
            char escape = text.charAt(position++);
            switch (escape) {
                case '"': builder.append('"'); break;
                case '\\': builder.append('\\'); break;
                case '/': builder.append('/'); break;
                case 'b': builder.append('\b'); break;
                case 'f': builder.append('\f'); break;
                case 'n': builder.append('\n'); break;
                case 'r': builder.append('\r'); break;
                case 't': builder.append('\t'); break;
                case 'u': builder.append(hexCharacter()); break;
                default: throw error("invalid escape");
            }
        }
        throw error("unterminated string");
    }

    private char hexCharacter() throws LaunchPlanException {
        if (position + 4 > text.length()) throw error("truncated \\u escape");
        int value = 0;
        for (int i = 0; i < 4; ++i) {
            int digit = Character.digit(text.charAt(position++), 16);
            if (digit < 0) throw error("invalid \\u escape");
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private BigDecimal number() throws LaunchPlanException {
        int start = position;
        consume('-');
        if (consume('0')) {
            // A leading zero may not be followed by more digits.
        } else if (!digits()) {
            throw error("invalid number");
        }
        if (consume('.') && !digits()) throw error("invalid fraction");
        if (consume('e') || consume('E')) {
            if (!consume('+')) consume('-');
            if (!digits()) throw error("invalid exponent");
        }
        if (position - start > 64) throw error("number too long");
        return new BigDecimal(text.substring(start, position));
    }

    private boolean digits() {
        int start = position;
        while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9')
            ++position;
        return position > start;
    }

    private void literal(String word) throws LaunchPlanException {
        if (!text.startsWith(word, position)) throw error("invalid literal");
        position += word.length();
    }

    private boolean consume(char c) {
        if (position < text.length() && text.charAt(position) == c) { ++position; return true; }
        return false;
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            ++position;
        }
    }

    private LaunchPlanException error(String detail) {
        return new LaunchPlanException(LaunchPlanException.Category.METADATA_PARSE,
                detail + " at offset " + position);
    }
}
