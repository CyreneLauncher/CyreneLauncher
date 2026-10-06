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
 * The launcher's input and page logic on the host: text entry under random editing, and the real
 * LauncherScreen driven by random input against a temporary instance, with a renderer that skips
 * GL and a Modrinth client and installer that answer instantly. Each Play is checked by consuming
 * its prepared launch the way the next application start would.
 */
public final class LauncherLogicTest {
    private static final Input.Action[] ACTIONS = Input.Action.values();

    public static void main(String[] args) throws Exception {
        versionPredicates();
        textEntry();
        long seeds = args.length > 0 ? Long.parseLong(args[0]) : 8;
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 6000;
        for (long seed = 1; seed <= seeds; seed++) screen(seed, steps);
        System.out.println("launcher logic passed: text entry, " + seeds + " x " + steps + " random screen steps");
    }

    // ------------------------------------------------------------------ versions

    private static void versionPredicates() {
        String[][] yes = {
            {"0.4.1+build.15", "*"}, {"0.4.1+build.15", ">=0.4.1"}, {"0.4.1+build.15", "0.4.1"},
            {"0.4.1+build.15", "~0.4.0"}, {"0.4.1+build.15", "^0.4.0-alpha5"}, {"1.18.2", "1.18.x"},
            {"1.18.2", ">=1.18 <1.19"}, {"1.18.2", "[\"1.17.x\", \"1.18.x\"]"}, {"0.19.5", ">=0.12.3"},
            {"17", ">=16"}, {"1.18.2", "~1.18.2-"}, {"0.4.0", ">0.4.0-alpha5"}, {"2.0.0", ">=1.0.0-beta.2"},
            {"0.77.0+1.18.2", ">=0.36.0"}, {"1.18.2", "1.18.2"}, {"8.0.0+17", "8.0.0"},
        };
        String[][] no = {
            {"0.4.1+build.15", "0.4.0-alpha5"}, {"0.4.1+build.15", "<0.4.1"}, {"1.18.2", "1.19.x"},
            {"1.18.2", ">=1.19"}, {"1.18.2", "~1.17"}, {"0.4.0-alpha5", ">=0.4.0"}, {"2.0.0", "^1.0"},
            {"1.18.2", "[\"1.16.x\", \"1.17.x\"]"}, {"0.14.9", ">=0.15.0"},
        };
        for (String[] c : yes) check(VersionPredicate.matches(c[0], c[1]), c[0] + " should match " + c[1]);
        for (String[] c : no) check(!VersionPredicate.matches(c[0], c[1]), c[0] + " should not match " + c[1]);
        check(VersionPredicate.matches("weird", "a.b.c"), "unparseable never blocks");
    }

    // ------------------------------------------------------------------ text entry

    /** Random editing keeps the caret on a code point boundary; cancel restores, accept keeps. */
    private static void textEntry() {
        Random random = new Random(7);
        String[] pieces = {"a", "Z", " ", "\u00e9", "\ud83d\ude00", "\u0007", "\n", "\u7a7a"};
        Input.Action[] edits = {Input.Action.LEFT, Input.Action.RIGHT, Input.Action.HOME, Input.Action.END,
                Input.Action.DELETE, Input.Action.FORWARD_DELETE};
        for (int round = 0; round < 2000; round++) {
            TextEntry entry = new TextEntry();
            String initial = round % 3 == 0 ? "" : "Sodium \ud83d\ude00";
            entry.begin(initial);
            for (int i = 0; i < 40; i++) {
                if (random.nextBoolean()) entry.insert(pieces[random.nextInt(pieces.length)]);
                else entry.edit(edits[random.nextInt(edits.length)]);
                String v = entry.value();
                int c = entry.caret();
                check(c >= 0 && c <= v.length(), "caret in range");
                check(c == 0 || c == v.length() || !Character.isLowSurrogate(v.charAt(c)), "caret on a code point");
                check(v.codePoints().noneMatch(cp -> cp < 32 || cp == 127), "no control characters");
            }
            String edited = entry.value();
            if (random.nextBoolean()) check(entry.accept().equals(edited), "accept keeps the edit");
            else check(entry.cancel().equals(initial) && entry.value().equals(initial), "cancel restores");
            check(!entry.active(), "inactive after finishing");
        }
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
        FakeInstaller(ModrinthClient m, Path mods, Random random) { super(m, mods, null, java.util.Map.of()); this.mods = mods; this.random = random; }

        @Override Plan plan(String projectId, String title) throws IOException {
            if (random.nextInt(8) == 0) throw new IOException("network unreachable");
            List<Entry> entries = new ArrayList<>();
            int n = random.nextInt(4);
            for (int i = 0; i < n; i++) {
                ModrinthClient.ModFile file = new ModrinthClient.ModFile("https://x", projectId + "-" + i + ".jar", "00", 1);
                entries.add(new Entry(title + " " + i, new ModrinthClient.Version(projectId, "1." + i, file, List.of()),
                        new ModInspector.ModInfo(projectId + i, title, Set.of(), java.util.Map.of(), java.util.Map.of(), List.of(), Set.of()),
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
        final LauncherConfig config;
        int relaunches, consumed, closes;
        TestScreen(LauncherConfig c, Renderer r, ModrinthClient m, ModInstaller i) { super(c, r, m, i); config = c; }
        @Override void presentNow() {}
        @Override void closeWindow() { closes++; }
        /** No system dialog on the host: editing stays inline. */
        @Override protected boolean beginSystemKeyboard(String title, int maxLength) { return false; }

        /** The next start consumes the request; only the imported instance's directory is the host's cwd. */
        @Override int relaunch(String intent) {
            relaunches++;
            InstanceStore.Instance instance = config.selectedInstance();
            try {
                LaunchRequest request = new LaunchPreparation(config).consume(intent);
                check(request != null && request.instanceId().equals(instance.id()), "request names the instance");
                check(instance.imported(), "consumed only where the working directory matches");
                consumed++;
            } catch (IOException failure) {
                check(!instance.imported(), "imported instance launch rejected: " + failure.getMessage());
            }
            return -1;
        }
    }

    private static void screen(long seed, int steps) throws Exception {
        Random random = new Random(seed);
        Path root = Files.createTempDirectory("launcher-test");
        Path runner = Files.createDirectories(root.resolve("runner"));
        Path game = Files.createDirectories(root.resolve("game/mods"));
        Files.writeString(runner.resolve("jvm-options.txt"), "-Xms1024m\n-Xmx1024m\n-XX:+UseParallelGC\n"
                + "-Dorbis.graphics.arenaMib=1792\n", StandardCharsets.UTF_8);
        // Shaped like the console's runner.conf, so Play's preparation accepts it.
        Files.writeString(runner.resolve("runner.conf"), "java_home=" + root.resolve("jdk") + "\njar=" + root.resolve("target.jar")
                + "\nworking_directory=" + root.resolve("game") + "\noutput_log=" + root.resolve("out.log")
                + "\ntemporary_directory=" + runner.resolve("tmp") + "\ndebug=true\ndisplay=gpu\ngraphics_bundle="
                + root.resolve("bundle") + "\nversion_metadata=" + root.resolve("version.json") + "\nlibraries="
                + root.resolve("libraries") + "\n", StandardCharsets.UTF_8);
        Path metadata = Files.writeString(root.resolve("version.json"),
                "{\"id\": \"fabric-loader-0.14.9.5-1.18.2\", \"mainClass\": \"net.fabricmc.loader.impl.launch.knot.KnotClient\"}");
        for (int i = 0; i < random.nextInt(30); i++)
            Files.write(game.resolve("mod" + i + (random.nextInt(4) == 0 ? ".jar.disabled" : ".jar")), new byte[]{1});
        System.setProperty("orbis.launcher.home", root.resolve("home").toString());
        System.setProperty("orbis.runner.root", runner.toString());
        System.setProperty("orbis.launcher.gameMetadata", metadata.toString());
        System.setProperty("user.dir", root.resolve("game").toString());
        LauncherConfig config = LauncherConfig.load();
        // A saved Microsoft account; without a client configuration its refresh fails safely.
        if (seed % 2 == 0) new AccountStore(config.home, config.runnerRoot).putMicrosoft(
                "0123456789abcdef0123456789abcdef", "Tester", "00000000-0000-0000-0000-000000000000", "refresh");
        FakeModrinth modrinth = new FakeModrinth(random);
        TestScreen screen = new TestScreen(config, new HeadlessRenderer(), modrinth,
                new FakeInstaller(modrinth, game, random));

        // Play directly once: focus the actions, Play is the first.
        String conf = Files.readString(runner.resolve("runner.conf")), options = Files.readString(runner.resolve("jvm-options.txt"));
        screen.apply("", List.of(Input.Action.FOCUS, Input.Action.CONFIRM));
        for (long deadline = System.nanoTime() + 5_000_000_000L; screen.applyResults(); Thread.sleep(1))
            if (System.nanoTime() > deadline) throw new AssertionError("Play did not finish");
        check(screen.relaunches == 1, "direct Play reaches the restart");
        check(conf.equals(Files.readString(runner.resolve("runner.conf"))), "failed restart restores runner.conf");
        check(options.equals(Files.readString(runner.resolve("jvm-options.txt"))), "failed restart restores jvm-options");
        check(!Files.exists(config.home.resolve("next-start")), "failed restart leaves no pending launch");
        screen.apply("", List.of(Input.Action.FOCUS));

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
        System.out.println("seed " + seed + ": " + steps + " steps, " + screen.relaunches + " Play ("
                + screen.consumed + " consumed), "
                + screen.closes + " Quit, mods now " + listCount(game));
    }

    private static long listCount(Path dir) throws IOException {
        try (var s = Files.list(dir)) { return s.count(); }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
