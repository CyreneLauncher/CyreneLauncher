package orbis.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Opens the console's system keyboard (orbis.graphics.TextInput, in the graphics adapter) when a
 * Minecraft text field or a sign gets focus while the controller was the last input, and writes what
 * the player accepted back into it; with a keyboard or mouse in use nothing opens. Chat sends what was
 * accepted. Driven once per client tick (ClientTickMixin), on the render thread.
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
    private static final int ENTER = 257;
    private static final int SIGN_ROWS = 4;

    private static Class<?> input;      // orbis.graphics.TextInput, absent off the console
    private static boolean disabled;
    private static Object lastSeen;     // the field or sign screen focused at the previous tick
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
        if (found == lastSeen) return;
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

    /** The focused, editable text field of the screen, or the screen itself for a sign. */
    private static Object findTarget(Object screen, ClassLoader game) throws ReflectiveOperationException {
        if (isA(screen, SIGN_SCREEN_1_20, game) || isA(screen, SIGN_SCREEN_1_18, game)) return screen;
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
