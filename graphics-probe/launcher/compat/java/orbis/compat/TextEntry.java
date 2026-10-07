package orbis.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Opens the console's system keyboard (orbis.graphics.TextInput, in the graphics adapter) when a
 * Minecraft text field gets focus, a sign is edited, or a book is opened at a page or signed, while the
 * controller was the last input, and writes what the player accepted back; with a keyboard or mouse in
 * use nothing opens. Chat sends what was accepted. Driven once per client tick (ClientTickMixin), on
 * the render thread.
 *
 * Minecraft is reached by its intermediary names, the same from 1.18.2 to 1.20.4 except where both
 * spellings are listed. Anything missing switches the feature off with one log line.
 */
public final class TextEntry {
    private static final String TEXT_FIELD = "net.minecraft.class_342";      // TextFieldWidget / EditBox
    private static final String PARENT = "net.minecraft.class_4069";         // ParentElement
    private static final String CHAT_SCREEN = "net.minecraft.class_408";
    private static final String SIGN_SCREEN_1_20 = "net.minecraft.class_7743"; // AbstractSignEditScreen
    private static final String SIGN_SCREEN_1_18 = "net.minecraft.class_498";  // SignEditScreen
    private static final String BOOK_SCREEN = "net.minecraft.class_473";      // BookEditScreen
    private static final int BOOK_TITLE_LENGTH = 15;                          // vanilla: length < 16
    private static final int ENTER = 257;
    private static final int SIGN_ROWS = 4;

    private static Class<?> input;      // orbis.graphics.TextInput, absent off the console
    private static boolean disabled;
    private static Object lastSeen;     // the field, sign screen or book page focused at the previous tick
    private static Object target;       // what the open dialog edits
    private static Object targetScreen;
    private static boolean open, closing;

    private TextEntry() {}

    public static void tick(Object client) {
        if (disabled) return;
        try {
            if (input == null) {
                // Through the game's loader: Fabric's Knot loads the graphics adapter itself, and the
                // GLFW facade notes the last input in that copy of TextInput, not the system loader's.
                input = Class.forName("orbis.graphics.TextInput", true, client.getClass().getClassLoader());
                System.out.println("orbis-compat: system keyboard for text fields ready");
            }
            step(client);
        } catch (ClassNotFoundException notConsole) {
            disabled = true;
        } catch (Throwable failure) {
            disabled = true;
            System.err.println("orbis-compat: system keyboard for text fields off: " + failure);
        }
    }

    private static void step(Object client) throws ReflectiveOperationException {
        Object screen = field(client, "field_1755");
        if (closing) {
            closing = !(Boolean) call(input, "cancel");
            return;
        }
        if (open) {
            if (screen != targetScreen) {
                // The screen closed under the dialog: drop it unapplied.
                open = false;
                closing = !(Boolean) call(input, "cancel");
                return;
            }
            int status = (Integer) call(input, "poll");
            if (status == 1) return;
            open = false;
            if (status == 2) apply(screen, (String) call(input, "result"));
            return;
        }
        ClassLoader game = client.getClass().getClassLoader();
        Object found = screen == null ? null : findTarget(screen, game);
        if (Objects.equals(found, lastSeen)) return;
        lastSeen = found;
        if (found == null || !(Boolean) call(input, "gamepadLast")) return;
        if (begin(found, screen, game)) {
            open = true;
            target = found;
            targetScreen = screen;
        } else {
            System.err.println("orbis-compat: the system keyboard did not open for " + found.getClass().getName());
        }
    }

    /** A book being written: its screen, and the page shown or its title being signed. */
    private static final class BookTarget {
        final Object screen;
        final boolean signing;
        final int page;

        BookTarget(Object screen, boolean signing, int page) {
            this.screen = screen;
            this.signing = signing;
            this.page = page;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof BookTarget)) return false;
            BookTarget o = (BookTarget) other;
            return o.screen == screen && o.signing == signing && o.page == page;
        }

        @Override public int hashCode() {
            return System.identityHashCode(screen) * 31 + page * 2 + (signing ? 1 : 0);
        }
    }

    /** The focused, editable text field of the screen, the screen itself for a sign, or a book page. */
    private static Object findTarget(Object screen, ClassLoader game) throws ReflectiveOperationException {
        if (isA(screen, SIGN_SCREEN_1_20, game) || isA(screen, SIGN_SCREEN_1_18, game)) return screen;
        if (isA(screen, BOOK_SCREEN, game))
            return new BookTarget(screen, (Boolean) field(screen, "field_2828"), (Integer) field(screen, "field_2840"));
        Object focused = screen;
        for (int depth = 0; depth < 8 && focused != null; ++depth) {
            if (isA(focused, TEXT_FIELD, game)) {
                Method editable = method(focused.getClass(), "method_20316");
                Method isFocused = method(focused.getClass(), "method_25370");
                boolean ok = (editable == null || (Boolean) editable.invoke(focused))
                        && (isFocused == null || (Boolean) isFocused.invoke(focused));
                return ok ? focused : null;
            }
            if (!isA(focused, PARENT, game)) return null;
            focused = call(focused, "method_25399");
        }
        return null;
    }

    private static boolean begin(Object found, Object screen, ClassLoader game) throws ReflectiveOperationException {
        if (found instanceof BookTarget) {
            BookTarget book = (BookTarget) found;
            if (book.signing)
                return (Boolean) call(input, "begin", (String) field(screen, "field_2847"), "Book title",
                                      BOOK_TITLE_LENGTH, false, 0);
            int pages = (Integer) call(screen, "method_17046");
            return (Boolean) call(input, "begin", (String) call(screen, "method_2427"),
                                  "Page " + (book.page + 1) + " of " + Math.max(1, pages), 1024, true, 0);
        }
        if (found == screen) {
            String[] rows = signRows(screen);
            return (Boolean) call(input, "begin", String.join("\n", rows), "Sign", 4 * 90, true, 0);
        }
        String text = (String) call(found, "method_1882");
        int limit = (Integer) call(found, "method_1861");
        boolean chat = isA(screen, CHAT_SCREEN, game);
        return (Boolean) call(input, "begin", text, chat ? "Chat" : "Text", limit, false, chat ? 1 : 0);
    }

    private static void apply(Object screen, String text) throws ReflectiveOperationException {
        ClassLoader game = screen.getClass().getClassLoader();
        if (target instanceof BookTarget) {
            BookTarget book = (BookTarget) target;
            if (book.signing) writeBookTitle(screen, text);
            else writeBookPages(screen, book.page, text);
            return;
        }
        if (target == screen) {
            writeSign(screen, text);
            return;
        }
        call(target, "method_1852", text);
        // Chat sends what was accepted, as the dialog's Send label says.
        if (isA(screen, CHAT_SCREEN, game)) call(screen, "method_25404", ENTER, 0, 0);
    }

    private static String[] signRows(Object screen) throws ReflectiveOperationException {
        Object rows = field(screen, "field_40425", "field_24285");
        return ((String[]) rows).clone();
    }

    /** One row per line; a single line is word-wrapped by the sign's own width check. */
    private static void writeSign(Object screen, String text) throws ReflectiveOperationException {
        Method fits = method(screen.getClass(), "method_45658", "method_27611");
        String[] lines = text.replace("\r", "").split("\n", -1);
        String[] rows = new String[SIGN_ROWS];
        if (lines.length == 1 && fits != null) {
            int row = 0;
            StringBuilder line = new StringBuilder();
            for (String word : text.trim().split("\\s+")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && !(Boolean) fits.invoke(screen, candidate)) {
                    if (row < SIGN_ROWS) rows[row++] = line.toString();
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (row < SIGN_ROWS) rows[row] = line.toString();
        } else {
            for (int i = 0; i < SIGN_ROWS; ++i) rows[i] = i < lines.length ? lines[i] : "";
        }
        Field current = field(screen.getClass(), "field_40428", "field_3029");
        Method setRow = method(screen.getClass(), "method_49913", "method_16205");
        if (current == null || setRow == null) throw new NoSuchMethodException("sign row setter");
        for (int i = 0; i < SIGN_ROWS; ++i) {
            String row = rows[i] == null ? "" : rows[i];
            while (fits != null && !row.isEmpty() && !(Boolean) fits.invoke(screen, row))
                row = row.substring(0, row.length() - 1);
            current.setInt(screen, i);
            setRow.invoke(screen, row);
        }
        current.setInt(screen, 0);
        Object selection = field(screen, "field_40429", "field_3032");
        if (selection != null) call(selection, "method_16204");
    }

    private static void writeBookTitle(Object screen, String text) throws ReflectiveOperationException {
        String title = text.replace("\n", " ").replace("\r", "");
        if (title.length() > BOOK_TITLE_LENGTH) title = title.substring(0, BOOK_TITLE_LENGTH);
        Field field = field(screen.getClass(), "field_2847");
        if (field == null) throw new NoSuchFieldException("book title");
        field.set(screen, title);
        Field dirty = field(screen.getClass(), "field_2837");
        if (dirty != null) dirty.setBoolean(screen, true);
        call(screen, "method_2413");                      // updateButtons: "Sign and Close" follows the title
        Object selection = field(screen, "field_24270");
        if (selection != null) call(selection, "method_16204");
    }

    /**
     * The accepted text goes on the page shown; what does not fit (the book's own check: 1024
     * characters and 14 lines) continues on the following pages, which are added as needed up to the
     * game's page limit, broken between words where a word fits.
     */
    @SuppressWarnings("unchecked")
    private static void writeBookPages(Object screen, int page, String text) throws ReflectiveOperationException {
        Method fits = method(screen.getClass(), "method_27593");
        List<String> pages = (List<String>) field(screen, "field_17116");
        if (fits == null || pages == null) throw new NoSuchMethodException("book page check");
        String rest = text.replace("\r", "");
        int index = page;
        while (true) {
            // The longest prefix that fits: a longer prefix never takes fewer lines, so a binary search.
            int cut = rest.length();
            if (!(Boolean) fits.invoke(screen, rest)) {
                int low = 0, high = rest.length();
                while (low < high) {
                    int middle = (low + high + 1) >>> 1;
                    if ((Boolean) fits.invoke(screen, rest.substring(0, middle))) low = middle;
                    else high = middle - 1;
                }
                cut = low;
            }
            // Break between words: at the cut if a space or line break is there (it is consumed, so
            // the next page does not start with it), else after the last one before it.
            boolean separator = false;
            if (cut < rest.length()) {
                char next = rest.charAt(cut);
                if (next == ' ' || next == '\n') {
                    separator = true;
                } else {
                    int breakAt = Math.max(rest.lastIndexOf(' ', cut - 1), rest.lastIndexOf('\n', cut - 1));
                    if (breakAt > 0) cut = breakAt + 1;
                }
            }
            String content = rest.substring(0, cut);
            if (index == page) {
                call(screen, "method_2439", content);   // setPageContent: the page shown, marked dirty
            } else {
                while (pages.size() <= index) {
                    int before = pages.size();
                    call(screen, "method_2436");         // appendNewPage, which stops at the page limit
                    if (pages.size() == before) break;
                }
                if (index >= pages.size()) break;
                pages.set(index, content);
            }
            rest = rest.substring(separator ? cut + 1 : cut);
            if (rest.isEmpty() || cut == 0) break;
            ++index;
        }
        call(screen, "method_27577");                    // invalidatePageContent: re-wrap the page shown
        call(screen, "method_2413");                     // updateButtons: page arrows follow new pages
        Object selection = field(screen, "field_24269");
        if (selection != null) call(selection, "method_16204");
    }

    // ---------------------------------------------------------------- reflection

    private static boolean isA(Object value, String className, ClassLoader game) {
        try {
            return Class.forName(className, false, game).isInstance(value);
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    private static Object call(Object owner, String name, Object... arguments) throws ReflectiveOperationException {
        Class<?> type = owner instanceof Class ? (Class<?>) owner : owner.getClass();
        Object receiver = owner instanceof Class ? null : owner;
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods())
                if (m.getName().equals(name) && m.getParameterCount() == arguments.length) {
                    m.setAccessible(true);
                    return m.invoke(receiver, arguments);
                }
        for (Method m : type.getMethods())
            if (m.getName().equals(name) && m.getParameterCount() == arguments.length) return m.invoke(receiver, arguments);
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Method method(Class<?> type, String... names) {
        for (String name : names) {
            for (Class<?> c = type; c != null; c = c.getSuperclass())
                for (Method m : c.getDeclaredMethods())
                    if (m.getName().equals(name)) {
                        m.setAccessible(true);
                        return m;
                    }
            for (Method m : type.getMethods())
                if (m.getName().equals(name)) return m;
        }
        return null;
    }

    private static Field field(Class<?> type, String... names) {
        for (String name : names)
            for (Class<?> c = type; c != null; c = c.getSuperclass())
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException next) {
                    // keep looking
                }
        return null;
    }

    private static Object field(Object owner, String... names) throws IllegalAccessException {
        Field f = field(owner.getClass(), names);
        return f == null ? null : f.get(owner);
    }
}
