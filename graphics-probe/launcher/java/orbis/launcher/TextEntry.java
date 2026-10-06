package orbis.launcher;

/** A single focused Unicode text field. The caret is measured in UTF-16 offsets at code point boundaries. */
final class TextEntry {
    private String original = "";
    private final StringBuilder value = new StringBuilder();
    private int caret;
    private boolean active;

    void begin(String initial) {
        original = initial == null ? "" : initial;
        value.setLength(0);
        value.append(original);
        caret = value.length();
        active = true;
    }

    void insert(String text) {
        if (!active || text == null || text.isEmpty()) return;
        StringBuilder clean = new StringBuilder(text.length());
        text.codePoints().filter(cp -> cp >= 32 && cp != 127 && Character.isValidCodePoint(cp)
                        && (cp < 0xd800 || cp > 0xdfff))
                .forEach(clean::appendCodePoint);
        value.insert(caret, clean);
        caret += clean.length();
    }

    void edit(Input.Action action) {
        if (!active) return;
        switch (action) {
            case LEFT -> { if (caret > 0) caret = value.offsetByCodePoints(caret, -1); }
            case RIGHT -> { if (caret < value.length()) caret = value.offsetByCodePoints(caret, 1); }
            case HOME -> caret = 0;
            case END -> caret = value.length();
            case DELETE -> {
                if (caret > 0) {
                    int previous = value.offsetByCodePoints(caret, -1);
                    value.delete(previous, caret);
                    caret = previous;
                }
            }
            case FORWARD_DELETE -> {
                if (caret < value.length()) value.delete(caret, value.offsetByCodePoints(caret, 1));
            }
            default -> { }
        }
    }

    String accept() {
        active = false;
        original = value.toString();
        return original;
    }

    String cancel() {
        active = false;
        value.setLength(0);
        value.append(original);
        caret = value.length();
        return original;
    }

    boolean active() { return active; }
    String value() { return value.toString(); }
    int caret() { return caret; }
}
