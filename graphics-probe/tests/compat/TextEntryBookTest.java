import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Drives TextEntry's book writing against a stand-in with BookEditScreen's intermediary member names. */
public final class TextEntryBookTest {
    /** A page fits in 40 characters and at most 3 lines; the book holds at most 4 pages. */
    static final class FakeBook {
        List<String> field_17116 = new ArrayList<>(List.of("old first page", ""));
        int field_2840;                 // currentPage
        boolean field_2837;             // dirty
        String field_2847 = "";         // title
        Object field_24269 = new FakeSelection(), field_24270 = new FakeSelection();
        int invalidated, buttonUpdates;

        boolean method_27593(String page) { return page.length() <= 40 && page.split("\n", -1).length <= 3; }
        void method_2439(String content) { field_17116.set(field_2840, content); field_2837 = true; }
        void method_2436() { if (field_17116.size() < 4) field_17116.add(""); }
        void method_27577() { ++invalidated; }
        void method_2413() { ++buttonUpdates; }
    }

    /** 1.21.6+: the page is an EditBoxWidget whose EditBox checks length and lines; same limits. */
    static final class FakeModernBook {
        List<String> field_17116 = new ArrayList<>(List.of("old first page", ""));
        int field_2840;                 // currentPage
        FakeBoxWidget field_60462 = new FakeBoxWidget();
        int pageUpdates, arrowUpdates;

        void method_2436() { if (field_17116.size() < 4) field_17116.add(""); }
        void method_71537() { field_60462.text = field_17116.get(field_2840); ++pageUpdates; }   // updatePage
        void method_71538() { ++arrowUpdates; }
    }

    static final class FakeBoxWidget {
        String text = "";
        FakeEditBox field_39509 = new FakeEditBox();
        String method_44405() { return text; }
    }

    static final class FakeEditBox {
        boolean method_44418() { return true; }                                   // hasMaxLength
        int method_44409() { return 40; }                                         // getMaxLength
        boolean method_71519(String text) { return text.split("\n", -1).length > 3; }  // exceedsMaxLines
    }

    /** Chat up to 1.21.8: keyPressed(key, scancode, modifiers). */
    static final class FakeChat {
        int key = -1;
        boolean method_25404(int key, int scancode, int modifiers) { this.key = key; return true; }
    }

    /** Chat from 1.21.9: keyPressed(KeyInput). */
    record FakeKeyInput(int key, int scancode, int modifiers) { }

    static final class FakeChat1219 {
        FakeKeyInput pressed;
        boolean method_25404(FakeKeyInput input) { pressed = input; return true; }
    }

    static final class FakeSelection {
        int ends;
        void method_16204() { ++ends; }
    }

    // 26.1+: Mojang's names, as Fabric runs them there.
    static final class FakeMojangBook {
        List<String> pages = new ArrayList<>(List.of("old first page", ""));
        int currentPage;
        FakeMojangBox page = new FakeMojangBox();
        int pageUpdates, arrowUpdates;
        void appendPageToBook() { if (pages.size() < 4) pages.add(""); }
        void updatePageContent() { page.text = pages.get(currentPage); ++pageUpdates; }
        void updateButtonVisibility() { ++arrowUpdates; }
    }

    static final class FakeMojangBox {
        String text = "";
        FakeMojangField textField = new FakeMojangField();
        String getValue() { return text; }
    }

    static final class FakeMojangField {
        boolean hasCharacterLimit() { return true; }
        int characterLimit() { return 40; }
        boolean overflowsLineLimit(String text) { return text.split("\n", -1).length > 3; }
    }

    record FakeKeyEvent(int key, int scancode, int modifiers) { }

    static final class FakeMojangChat {
        FakeKeyEvent pressed;
        boolean keyPressed(FakeKeyEvent event) { pressed = event; return true; }
    }

    static final class FakeMojangSign {
        String[] messages = {"", "", "", ""};
        int line;
        FakeMojangSelection signField = new FakeMojangSelection();
        void setMessage(String text) { messages[line] = text; }
        // The width check is a lambda whose number may change between releases.
        private boolean lambda$init$2(String text) { return text.length() <= 12; }
    }

    static final class FakeMojangSelection {
        int ends;
        void setCursorToEnd() { ++ends; }
    }

    static int failures;

    static void check(boolean ok, String what) {
        if (!ok) { System.out.println("FAIL " + what); ++failures; }
    }

    public static void main(String[] args) throws Exception {
        Class<?> entry = Class.forName("orbis.compat.TextEntry");
        Method pages = entry.getDeclaredMethod("writeBookPages", Object.class, int.class, String.class);
        Method title = entry.getDeclaredMethod("writeBookTitle", Object.class, String.class);
        pages.setAccessible(true);
        title.setAccessible(true);

        FakeBook book = new FakeBook();
        pages.invoke(null, book, 0, "short page");
        check(book.field_17116.equals(List.of("short page", "")) && book.field_2837 && book.invalidated == 1,
              "a page that fits replaces only the page shown");

        book = new FakeBook();
        String text = "alpha bravo charlie delta echo foxtrot golf hotel india juliett kilo lima mike";
        pages.invoke(null, book, 0, text);
        check(book.field_17116.size() == 2, "overflow adds a page: " + book.field_17116);
        for (String page : book.field_17116) check(book.method_27593(page), "every page fits: [" + page + "]");
        check(String.join(" ", book.field_17116).trim().replaceAll("\\s+", " ").equals(text),
              "no word lost or doubled: " + book.field_17116);
        check(book.field_17116.get(1).startsWith("golf"), "pages break between words: " + book.field_17116);

        book = new FakeBook();
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 60; ++i) huge.append("word").append(i).append(' ');
        pages.invoke(null, book, 0, huge.toString());
        check(book.field_17116.size() == 4, "stops at the page limit: " + book.field_17116.size());

        book = new FakeBook();
        book.field_2840 = 1;
        pages.invoke(null, book, 1, "one\ntwo\nthree\nfour");
        check(book.field_17116.get(0).equals("old first page") && book.field_17116.get(1).equals("one\ntwo\nthree")
              && book.field_17116.get(2).equals("four"), "line limit moves the fourth line on: " + book.field_17116);

        book = new FakeBook();
        title.invoke(null, book, "A very long book title\nindeed");
        check(book.field_2847.equals("A very long boo") && book.field_2837 && book.buttonUpdates == 1
              && ((FakeSelection) book.field_24270).ends == 1, "title is one line of at most 15 characters: " + book.field_2847);

        FakeModernBook modern = new FakeModernBook();
        pages.invoke(null, modern, 0, text);
        check(modern.field_17116.size() == 2 && modern.field_17116.get(1).startsWith("golf"),
              "1.21.6+: overflow adds a page, broken between words: " + modern.field_17116);
        check(String.join(" ", modern.field_17116).trim().replaceAll("\\s+", " ").equals(text),
              "1.21.6+: no word lost or doubled: " + modern.field_17116);
        check(modern.field_60462.text.equals(modern.field_17116.get(0)) && modern.pageUpdates == 1
              && modern.arrowUpdates == 1, "1.21.6+: the page shown is reloaded into the edit box");

        modern = new FakeModernBook();
        modern.field_2840 = 1;
        pages.invoke(null, modern, 1, "one\ntwo\nthree\nfour");
        check(modern.field_17116.get(0).equals("old first page") && modern.field_17116.get(1).equals("one\ntwo\nthree")
              && modern.field_17116.get(2).equals("four") && modern.field_60462.text.equals("one\ntwo\nthree"),
              "1.21.6+: the edit box's line limit moves the fourth line on: " + modern.field_17116);

        Method enter = entry.getDeclaredMethod("pressEnter", Object.class);
        enter.setAccessible(true);
        FakeChat chat = new FakeChat();
        enter.invoke(null, chat);
        check(chat.key == 257, "chat sends with keyPressed(int, int, int)");
        FakeChat1219 chat1219 = new FakeChat1219();
        enter.invoke(null, chat1219);
        check(chat1219.pressed != null && chat1219.pressed.key() == 257, "chat sends with keyPressed(KeyInput) (1.21.9+)");

        java.lang.reflect.Field mojang = entry.getDeclaredField("mojang");
        mojang.setAccessible(true);
        mojang.setBoolean(null, true);
        FakeMojangBook current = new FakeMojangBook();
        pages.invoke(null, current, 0, text);
        check(current.pages.size() == 2 && current.pages.get(1).startsWith("golf")
              && String.join(" ", current.pages).trim().replaceAll("\\s+", " ").equals(text),
              "26.1+: the book is written through Mojang's names: " + current.pages);
        check(current.page.text.equals(current.pages.get(0)) && current.pageUpdates == 1 && current.arrowUpdates == 1,
              "26.1+: the page shown is reloaded into the edit box");
        FakeMojangChat mojangChat = new FakeMojangChat();
        enter.invoke(null, mojangChat);
        check(mojangChat.pressed != null && mojangChat.pressed.key() == 257, "26.1+: chat sends with keyPressed(KeyEvent)");
        Method sign = entry.getDeclaredMethod("writeSign", Object.class, String.class);
        sign.setAccessible(true);
        FakeMojangSign signScreen = new FakeMojangSign();
        sign.invoke(null, signScreen, "one two three four five six");
        check(java.util.Arrays.asList(signScreen.messages).equals(List.of("one two", "three four", "five six", ""))
              && signScreen.signField.ends == 1, "26.1+: a sign wraps by its own width check: "
              + java.util.Arrays.toString(signScreen.messages));
        mojang.setBoolean(null, false);

        if (failures > 0) System.exit(1);
        System.out.println("TextEntry book writing passed");
    }
}
