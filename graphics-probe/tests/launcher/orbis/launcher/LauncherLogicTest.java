package orbis.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The launcher's input and page logic on the host: the on-screen keyboard over every reachable key
 * and action, and the real LauncherScreen driven by random input against a temporary instance,
 * with a renderer that skips GL and a Modrinth client and installer that answer instantly.
 */
public final class LauncherLogicTest {
    private static final Input.Action[] ACTIONS = Input.Action.values();

    public static void main(String[] args) throws Exception {
        keyboard();
        long seeds = args.length > 0 ? Long.parseLong(args[0]) : 8;
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 6000;
        for (long seed = 1; seed <= seeds; seed++) screen(seed, steps);
        System.out.println("launcher logic passed: keyboard exhaustive, " + seeds + " x " + steps + " random screen steps");
    }

    // ------------------------------------------------------------------ keyboard

    private static void keyboard() {
        // Every reachable (row, col) under every action, from every reachable state.
        Deque<int[]> todo = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        todo.add(new int[0]);
        int states = 0;
        while (!todo.isEmpty()) {
            int[] path = todo.poll();
            OnScreenKeyboard k = replay(path);
            if (!seen.add(k.row() + "," + k.col())) continue;
            states++;
            for (Input.Action a : ACTIONS) {
                OnScreenKeyboard copy = replay(path);
                StringBuilder q = new StringBuilder("ab");
                copy.apply(a, q);
                check(copy.row() >= 0 && copy.row() <= OnScreenKeyboard.SPECIAL_ROW, "row in range");
                int len = copy.row() == OnScreenKeyboard.SPECIAL_ROW ? OnScreenKeyboard.SPECIAL.length
                        : OnScreenKeyboard.ROWS[copy.row()].length();
                check(copy.col() >= 0 && copy.col() < len, "col in range");
                int[] next = java.util.Arrays.copyOf(path, path.length + 1);
                next[path.length] = a.ordinal();
                todo.add(next);
            }
        }
        int expected = 0;
        for (String row : OnScreenKeyboard.ROWS) expected += row.length();
        expected += OnScreenKeyboard.SPECIAL.length;
        check(states == expected, "every key reachable (" + states + " of " + expected + ")");

        // Typing a word by moving to each letter.
        OnScreenKeyboard k = new OnScreenKeyboard();
        StringBuilder q = new StringBuilder();
        for (char c : "sodium extra".toCharArray()) moveTo(k, c, q);
        check(q.toString().equals("sodium extra"), "typed by moving: '" + q + "'");
        // Delete and Search on the special row; Back closes.
        while (k.row() != OnScreenKeyboard.SPECIAL_ROW) k.apply(Input.Action.DOWN, q);
        while (k.col() > 1) k.apply(Input.Action.LEFT, q);
        while (k.col() < 1) k.apply(Input.Action.RIGHT, q);
        k.apply(Input.Action.CONFIRM, q);
        check(q.toString().equals("sodium extr"), "special Delete");
        k.apply(Input.Action.RIGHT, q);
        check(k.apply(Input.Action.CONFIRM, q) == OnScreenKeyboard.Result.SEARCH, "special Search");
        check(k.apply(Input.Action.BACK, q) == OnScreenKeyboard.Result.CLOSE, "back closes");
        StringBuilder empty = new StringBuilder();
        k.apply(Input.Action.DELETE, empty);
        check(empty.length() == 0, "delete on empty");
    }

    private static OnScreenKeyboard replay(int[] path) {
        OnScreenKeyboard k = new OnScreenKeyboard();
        StringBuilder q = new StringBuilder();
        for (int a : path) k.apply(ACTIONS[a], q);
        return k;
    }

    private static void moveTo(OnScreenKeyboard k, char c, StringBuilder q) {
        int row = -1, col = -1;
        for (int r = 0; r < OnScreenKeyboard.ROWS.length; r++)
            if (OnScreenKeyboard.ROWS[r].indexOf(c) >= 0) { row = r; col = OnScreenKeyboard.ROWS[r].indexOf(c); }
        check(row >= 0, "character on the keyboard: " + c);
        while (k.row() != row) k.apply(Input.Action.DOWN, q);
        while (k.col() > col) k.apply(Input.Action.LEFT, q);
        while (k.col() < col) k.apply(Input.Action.RIGHT, q);
        k.apply(Input.Action.CONFIRM, q);
    }

    // ------------------------------------------------------------------ screen

    /** A renderer without GL: geometry and text metrics only. */
    static final class HeadlessRenderer extends Renderer {
        HeadlessRenderer() {
            java.util.Arrays.fill(glyphWidth, 6);
            glyphWidth[' '] = 4;
        }
        @Override void setUp() {}
        @Override void begin(int w, int h) { width = w; height = h; s = h / 1080f; }
        @Override void end() {}
        @Override void rect(float x, float y, float w, float h, int color) {}
        @Override void text(String text, float x, float y, float scale, int color) {}
    }

    static final class FakeModrinth extends ModrinthClient {
        final Random random;
        FakeModrinth(Random random) { super("1.18.2"); this.random = random; }

        @Override List<Hit> search(String query) {
            List<Hit> hits = new ArrayList<>();
            int n = random.nextInt(5) == 0 ? 0 : random.nextInt(45);
            for (int i = 0; i < n; i++)
                hits.add(new Hit("p" + i, "mod-" + i, "Mod " + i + " " + query, "A mod with a description that is "
                        + "x".repeat(random.nextInt(300)), random.nextInt(1_000_000), "author" + i));
            return hits;
        }
    }

    static final class FakeInstaller extends ModInstaller {
        final Random random;
        final Path mods;
        FakeInstaller(ModrinthClient m, Path mods, Random random) { super(m, mods, null); this.mods = mods; this.random = random; }

        @Override Plan plan(String projectId, String title) throws IOException {
            if (random.nextInt(8) == 0) throw new IOException("network unreachable");
            List<Entry> entries = new ArrayList<>();
            int n = random.nextInt(4);
            for (int i = 0; i < n; i++) {
                ModrinthClient.ModFile file = new ModrinthClient.ModFile("https://x", projectId + "-" + i + ".jar", "00", 1);
                entries.add(new Entry(title + " " + i, new ModrinthClient.Version(projectId, "1." + i, file, List.of()),
                        new ModInspector.ModInfo(projectId + i, title, Set.of(), java.util.Map.of(), List.of(), Set.of()),
                        new byte[]{1}, null));
            }
            List<String> blockers = new ArrayList<>(), warnings = new ArrayList<>();
            if (random.nextInt(3) == 0) blockers.add("Blocked because " + "reason ".repeat(random.nextInt(40)));
            if (random.nextInt(3) == 0) warnings.add("Warning " + "detail ".repeat(random.nextInt(40)));
            return new Plan(entries, blockers, warnings);
        }

        @Override void install(Plan plan) throws IOException {
            for (Entry e : plan.entries()) Files.write(mods.resolve(e.version().file().filename()), e.data());
        }
    }

    static final class TestScreen extends LauncherScreen {
        int relaunches, closes;
        TestScreen(LauncherConfig c, Renderer r, ModrinthClient m, ModInstaller i) { super(c, r, m, i); }
        @Override void presentNow() {}
        @Override int relaunch() { relaunches++; return -1; }
        @Override void closeWindow() { closes++; }
    }

    private static void screen(long seed, int steps) throws Exception {
        Random random = new Random(seed);
        Path root = Files.createTempDirectory("launcher-test");
        Path runner = Files.createDirectories(root.resolve("runner"));
        Path game = Files.createDirectories(root.resolve("game/mods"));
        Files.writeString(runner.resolve("jvm-options.txt"), "-Xms1024m\n-Xmx1024m\n-XX:+UseParallelGC\n"
                + "-Dorbis.graphics.arenaMib=1792\n", StandardCharsets.UTF_8);
        Files.writeString(runner.resolve("runner.conf"), "jar=x\ndebug=true\n", StandardCharsets.UTF_8);
        Path metadata = Files.writeString(root.resolve("version.json"),
                "{\"id\": \"fabric-loader-0.14.9.5-1.18.2\", \"mainClass\": \"net.fabricmc.loader.impl.launch.knot.KnotClient\"}");
        for (int i = 0; i < random.nextInt(30); i++)
            Files.write(game.resolve("mod" + i + (random.nextInt(4) == 0 ? ".jar.disabled" : ".jar")), new byte[]{1});
        System.setProperty("orbis.launcher.home", root.resolve("home").toString());
        System.setProperty("orbis.runner.root", runner.toString());
        System.setProperty("orbis.launcher.gameMetadata", metadata.toString());
        System.setProperty("user.dir", root.resolve("game").toString());
        LauncherConfig config = LauncherConfig.load();
        FakeModrinth modrinth = new FakeModrinth(random);
        TestScreen screen = new TestScreen(config, new HeadlessRenderer(), modrinth,
                new FakeInstaller(modrinth, game, random));

        Deque<String> recent = new ArrayDeque<>();
        for (int step = 0; step < steps; step++) {
            List<Input.Action> actions = new ArrayList<>();
            int n = random.nextInt(3);
            for (int i = 0; i < n; i++) {
                // Bias towards confirming and moving, so deep pages are reached often.
                int pick = random.nextInt(14);
                actions.add(pick < 4 ? Input.Action.CONFIRM : pick < 7 ? Input.Action.DOWN
                        : pick < 8 ? Input.Action.BACK : ACTIONS[random.nextInt(ACTIONS.length)]);
            }
            String typed = random.nextInt(10) == 0 ? "ab c".substring(0, random.nextInt(5)) : "";
            recent.add(actions + " '" + typed + "'");
            if (recent.size() > 12) recent.poll();
            try {
                screen.apply(typed, actions);
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (screen.applyResults()) {
                    if (System.nanoTime() > deadline) throw new AssertionError("background work did not finish");
                    Thread.sleep(1);
                }
                screen.frame(1920, 1080);
                screen.frame(1280, 720);
            } catch (Throwable failure) {
                System.err.println("seed " + seed + " step " + step + "; last inputs: " + recent);
                throw failure;
            }
        }
        System.out.println("seed " + seed + ": " + steps + " steps, " + screen.relaunches + " Play, "
                + screen.closes + " Quit, mods now " + listCount(game));
    }

    private static long listCount(Path dir) throws IOException {
        try (var s = Files.list(dir)) { return s.count(); }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
