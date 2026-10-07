package orbis.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modrinth project descriptions (Markdown with HTML mixed in) as blocks the launcher can lay out:
 * headings, paragraphs, list items, quotes, code lines, rules and images. Formatting the bitmap
 * font cannot show (emphasis, links, tables) is reduced to its text.
 */
final class Markdown {
    enum Kind { HEADING, TEXT, BULLET, QUOTE, CODE, IMAGE, RULE }

    /** level: heading level or list depth; text: the image URL for IMAGE. */
    record Block(Kind kind, int level, String text) {}

    private static final Pattern IMG_TAG = Pattern.compile("(?is)<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>");
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");
    private static final Pattern REF_LINK = Pattern.compile("\\[([^\\]]+)\\]\\[[^\\]]*\\]");
    private static final Pattern AUTOLINK = Pattern.compile("<(https?://[^>\\s]+)>");
    private static final Pattern HEADING_TAG = Pattern.compile("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]{0,2000}>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");
    private static final Pattern ORDERED = Pattern.compile("^(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern MARKER = Pattern.compile("(\\*\\*|__|~~)(.+?)\\1");
    private static final Pattern SINGLE = Pattern.compile("(?<![\\w*])([*_])(?=\\S)(.+?)(?<=\\S)\\1(?![\\w*])");
    private static final Pattern CODE_SPAN = Pattern.compile("`+([^`]*)`+");
    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
            Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("mdash", "-"), Map.entry("ndash", "-"),
            Map.entry("hellip", "..."), Map.entry("copy", "(c)"), Map.entry("reg", "(R)"), Map.entry("trade", "(TM)"),
            Map.entry("rsquo", "'"), Map.entry("lsquo", "'"), Map.entry("rdquo", "\""), Map.entry("ldquo", "\""),
            Map.entry("bull", "*"), Map.entry("middot", "*"), Map.entry("times", "x"), Map.entry("rarr", "->"));

    private Markdown() {}

    static List<Block> parse(String source) {
        List<Block> out = new ArrayList<>();
        if (source == null) return out;
        String text = html(source.replace("\r\n", "\n").replace('\r', '\n'));
        StringBuilder paragraph = new StringBuilder();
        boolean fenced = false;
        for (String raw : text.split("\n", -1)) {
            String line = raw.replace("\t", "    ");
            String trimmed = line.strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flush(paragraph, out);
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                out.add(new Block(Kind.CODE, 0, line.stripTrailing()));
                continue;
            }
            if (trimmed.isEmpty()) {
                flush(paragraph, out);
                continue;
            }
            if (trimmed.matches("^([-*_])(\\s*\\1){2,}$")) {
                flush(paragraph, out);
                out.add(new Block(Kind.RULE, 0, ""));
                continue;
            }
            if (trimmed.matches("^=+$|^-+$") && paragraph.length() > 0) {
                // A setext heading: the paragraph so far is its text.
                String heading = paragraph.toString().strip();
                paragraph.setLength(0);
                out.add(new Block(Kind.HEADING, trimmed.startsWith("=") ? 1 : 2, inline(heading)));
                continue;
            }
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                if (level <= 6 && (level == trimmed.length() || trimmed.charAt(level) == ' ')) {
                    flush(paragraph, out);
                    String heading = trimmed.substring(level).replaceAll("\\s#+\\s*$", "").strip();
                    images(heading, out, Kind.HEADING, level);
                    continue;
                }
            }
            if (trimmed.startsWith(">")) {
                flush(paragraph, out);
                String quoted = trimmed.replaceFirst("^(>\\s?)+", "");
                if (!quoted.isEmpty()) images(quoted, out, Kind.QUOTE, 0);
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            Matcher ordered = ORDERED.matcher(trimmed);
            if (trimmed.length() > 1 && "-*+".indexOf(trimmed.charAt(0)) >= 0 && trimmed.charAt(1) == ' ') {
                flush(paragraph, out);
                images(trimmed.substring(2).strip(), out, Kind.BULLET, Math.min(3, indent / 2));
                continue;
            }
            if (ordered.matches()) {
                flush(paragraph, out);
                images(ordered.group(1) + ". " + ordered.group(2).strip(), out, Kind.BULLET, -1 - Math.min(3, indent / 2));
                continue;
            }
            if (trimmed.startsWith("|")) {
                flush(paragraph, out);
                if (trimmed.matches("^[|:\\-\\s]+$")) continue;
                String row = String.join("   ", java.util.Arrays.stream(trimmed.split("\\|"))
                        .map(String::strip).filter(cell -> !cell.isEmpty()).toList());
                images(row, out, Kind.TEXT, 0);
                continue;
            }
            if (indent >= 4 && paragraph.length() == 0) {
                out.add(new Block(Kind.CODE, 0, line.substring(4).stripTrailing()));
                continue;
            }
            if (paragraph.length() > 0) paragraph.append(line.endsWith("  ") ? "\n" : " ");
            paragraph.append(trimmed);
        }
        flush(paragraph, out);
        // Collapse runs of rules and drop empty text.
        List<Block> clean = new ArrayList<>();
        for (Block b : out) {
            if (b.kind() != Kind.IMAGE && b.kind() != Kind.RULE && b.kind() != Kind.CODE && b.text().isBlank()) continue;
            if (b.kind() == Kind.RULE && !clean.isEmpty() && clean.get(clean.size() - 1).kind() == Kind.RULE) continue;
            clean.add(b);
        }
        return clean;
    }

    private static void flush(StringBuilder paragraph, List<Block> out) {
        if (paragraph.length() == 0) return;
        for (String part : paragraph.toString().split("\n")) images(part, out, Kind.TEXT, 0);
        paragraph.setLength(0);
    }

    /** Text with its images split out into their own blocks, in order. */
    private static void images(String text, List<Block> out, Kind kind, int level) {
        Matcher m = MD_IMAGE.matcher(text);
        int at = 0;
        StringBuilder rest = new StringBuilder();
        List<Block> found = new ArrayList<>();
        while (m.find()) {
            rest.append(text, at, m.start());
            String url = m.group(2);
            if (showable(url)) found.add(new Block(Kind.IMAGE, 0, url));
            at = m.end();
        }
        rest.append(text.substring(at));
        // An image inside a link, [![alt](img)](url), leaves "[](url)" behind: drop it.
        String remaining = inline(rest.toString().replaceAll("\\[\\s*\\]\\([^)]*\\)", ""));
        if (!remaining.isBlank()) out.add(new Block(kind, level, remaining));
        out.addAll(found);
    }

    /** Badges (SVG, shields) are drawn by browsers, not by image decoders; skip them. */
    static boolean showable(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        if (!u.startsWith("https://") && !u.startsWith("http://")) return false;
        String path = u.replaceAll("[?#].*$", "");
        return !path.endsWith(".svg") && !u.contains("shields.io") && !u.contains("badge")
                && !u.contains("img.shields") && !u.contains("bstats.org");
    }

    /** HTML reduced to Markdown: images, headings, breaks and list items kept; other tags dropped. */
    private static String html(String text) {
        text = text.replaceAll("(?s)<!--.*?-->", "");
        text = IMG_TAG.matcher(text).replaceAll(m -> Matcher.quoteReplacement("\n\n![](" + m.group(1) + ")\n\n"));
        text = HEADING_TAG.matcher(text).replaceAll(m -> Matcher.quoteReplacement(
                "\n\n" + "#".repeat(Integer.parseInt(m.group(1))) + " " + m.group(2).replace('\n', ' ') + "\n\n"));
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</?(p|div|center|details|summary|table|tr|ul|ol|blockquote)\\b[^>]*>", "\n\n");
        text = text.replaceAll("(?i)<li\\b[^>]*>", "\n- ");
        text = text.replaceAll("(?i)</li\\s*>", "\n");
        text = text.replaceAll("(?i)</?(td|th)\\b[^>]*>", " | ");
        text = text.replaceAll("(?i)<hr\\s*/?>", "\n\n---\n\n");
        return TAG.matcher(text).replaceAll("");
    }

    /** Inline Markdown to plain text: links keep their words, emphasis and code marks go. */
    static String inline(String text) {
        text = AUTOLINK.matcher(text).replaceAll("$1");
        text = LINK.matcher(text).replaceAll(m -> Matcher.quoteReplacement(
                m.group(1).isBlank() ? "" : m.group(1)));
        text = REF_LINK.matcher(text).replaceAll("$1");
        text = CODE_SPAN.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
        for (int pass = 0; pass < 3; pass++) {
            text = MARKER.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(2)));
            text = SINGLE.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(2)));
        }
        text = text.replace("\\*", "*").replace("\\_", "_").replace("\\#", "#").replace("\\[", "[")
                .replace("\\]", "]").replace("\\`", "`");
        text = ENTITY.matcher(text).replaceAll(m -> Matcher.quoteReplacement(entity(m.group(1), m.group())));
        // Emoji and other symbols the font has no glyph for would show as '?': drop them.
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (cp < 0x2000 || (cp >= 0x2010 && cp <= 0x2033) || cp == 0x2192 || cp == 0x2190 || cp == 0x2022
                    || cp == 0x2026 || cp == 0x2212 || cp == 0x25cf) out.appendCodePoint(cp);
        });
        return out.toString().replaceAll("[ \\t]{2,}", " ").strip();
    }

    private static String entity(String name, String whole) {
        try {
            if (name.startsWith("#x")) return new String(Character.toChars(Integer.parseInt(name.substring(2), 16)));
            if (name.startsWith("#")) return new String(Character.toChars(Integer.parseInt(name.substring(1))));
        } catch (IllegalArgumentException invalid) {
            return "";
        }
        return ENTITIES.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), whole);
    }
}
