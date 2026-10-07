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

    static final class FakeSelection {
        int ends;
        void method_16204() { ++ends; }
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

        if (failures > 0) System.exit(1);
        System.out.println("TextEntry book writing passed");
    }
}
