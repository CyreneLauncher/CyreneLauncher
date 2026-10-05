package orbis.launcher;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.opengl.GL;

import orbis.graphics.NativePlatform;

import static orbis.launcher.Renderer.*;
import static org.lwjgl.glfw.GLFW.*;

/**
 * The launcher's screen: the instance with Play, Mods, Settings and Quit; the installed mods; and a
 * Modrinth browser whose installs are checked for the console first (ModInstaller). Network and
 * file work runs on a background thread; its results are applied on this, the GL thread.
 */
class LauncherScreen {
    private enum Page { HOME, SETTINGS, MODS, BROWSE, DETAIL }

    private final LauncherConfig config;
    private final CrashAnalyzer.Report crash;
    private final Renderer r;
    private final Input input = new Input();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "launcher worker");
        t.setDaemon(true);
        return t;
    });
    private final Queue<Runnable> results = new ConcurrentLinkedQueue<>();
    private final ModrinthClient modrinth;
    private final ModInstaller installer;

    private long window;
    private Page page = Page.HOME;
    private int selected, scroll;
    private String status = "";
    private int statusColor = DIM;
    private boolean dirty, busy;

    // Mods page.
    private List<Path> mods = new ArrayList<>();
    private Path removeArmed;
    private long removeArmedUntil;
    // Browse and detail pages.
    private final StringBuilder query = new StringBuilder();
    private List<ModrinthClient.Hit> hits = List.of();
    private boolean keyboardOpen;
    private final OnScreenKeyboard keys = new OnScreenKeyboard();
    private ModrinthClient.Hit chosen;
    private ModInstaller.Plan plan;

    LauncherScreen(LauncherConfig config) {
        this(config, new Renderer(), new ModrinthClient(config.gameVersion()), null);
    }

    /** Tests pass a renderer without GL and a Modrinth client and installer that do not use the network. */
    LauncherScreen(LauncherConfig config, Renderer renderer, ModrinthClient modrinth, ModInstaller installer) {
        this.config = config;
        this.crash = config.lastSession();
        this.r = renderer;
        this.modrinth = modrinth;
        String bundle = System.getProperty("runner.graphics.bundle");
        this.installer = installer != null ? installer
                : new ModInstaller(modrinth, config.modsDir(), bundle == null ? null : Path.of(bundle),
                        config.builtInVersions());
    }

    void run() throws IOException {
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(1920, 1080, "Orbis Launcher", 0, 0);
        if (window == 0) throw new IllegalStateException("window creation failed");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        input.install(window);
        r.setUp();
        int[] w = new int[1], h = new int[1];
        try (GLFWGamepadState pad = GLFWGamepadState.calloc()) {
            while (!glfwWindowShouldClose(window)) {
                glfwPollEvents();
                try {
                    applyResults();
                    input.poll(pad);
                    apply(input.takeTyped(), input.actions());
                    glfwGetFramebufferSize(window, w, h);
                    frame(w[0], h[0]);
                } catch (RuntimeException | LinkageError | AssertionError failure) {
                    internalError(failure);
                } catch (VirtualMachineError fatal) {
                    System.err.println("launcher: fatal error");
                    fatal.printStackTrace();
                    throw fatal;
                }
                glfwSwapBuffers(window);
            }
        }
        glfwDestroyWindow(window);
        glfwTerminate();
        System.exit(0);
    }

    // ------------------------------------------------------------------ background work

    private interface Work<T> { T run() throws Exception; }
    private interface Done<T> { void accept(T value); }

    private <T> void background(String message, Work<T> work, Done<T> done) {
        busy = true;
        setStatus(message, DIM);
        worker.execute(() -> {
            try {
                T value = work.run();
                results.add(() -> { busy = false; done.accept(value); });
            } catch (Exception failure) {
                String text = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                results.add(() -> { busy = false; setStatus("Failed: " + text, ERROR); });
            }
        });
    }

    // ------------------------------------------------------------------ input

    /** One frame's input: keyboard text and actions. */
    void apply(String typed, List<Input.Action> actions) {
        if (page == Page.BROWSE && !typed.isEmpty() && !busy) query.append(typed);
        for (Input.Action a : actions) {
            if (keyboardOpen) keyboard(a);
            else if (!busy || a == Input.Action.BACK) act(a);
        }
    }

    /** Applies finished background work; returns whether some is still running. */
    boolean applyResults() {
        for (Runnable result; (result = results.poll()) != null; ) result.run();
        return busy;
    }

    void frame(int framebufferWidth, int framebufferHeight) {
        r.begin(framebufferWidth, framebufferHeight);
        draw();
        r.end();
    }

    /** An unexpected error in a frame: logged with its stack (launcher.log) and shown, not fatal. */
    void internalError(Throwable failure) {
        System.err.println("launcher: internal error on page " + page);
        failure.printStackTrace();
        busy = false;
        keyboardOpen = false;
        selected = 0;
        scroll = 0;
        setStatus("Internal error: " + failure + " (details in launcher.log)", ERROR);
    }

    // Platform effects, replaced in tests.
    void presentNow() {
        frame(r.width, r.height);
        glfwSwapBuffers(window);
    }

    int relaunch() {
        return NativePlatform.nRelaunch();
    }

    void closeWindow() {
        glfwSetWindowShouldClose(window, true);
    }

    private void act(Input.Action a) {
        int count = items();
        switch (a) {
            case UP -> { if (count > 0) selected = (selected + count - 1) % count; }
            case DOWN -> { if (count > 0) selected = (selected + 1) % count; }
            case LEFT -> adjust(-1);
            case RIGHT -> adjust(+1);
            case CONFIRM -> confirm();
            case BACK -> back();
            case ALT2 -> { if (page == Page.MODS) remove(); }
            case MENU -> { if (page == Page.BROWSE) keyboardOpen = true; }
            case DELETE -> { if (page == Page.BROWSE && query.length() > 0) query.setLength(query.length() - 1); }
            default -> { }
        }
    }

    private int items() {
        return switch (page) {
            case HOME -> 4;
            case SETTINGS -> 5;
            case MODS -> 1 + mods.size();
            case BROWSE -> 1 + hits.size();
            case DETAIL -> plan != null && plan.installable() ? 2 : 1;
        };
    }

    private void go(Page next, int select) {
        page = next;
        selected = select;
        scroll = 0;
    }

    private void confirm() {
        switch (page) {
            case HOME -> {
                switch (selected) {
                    case 0 -> play();
                    case 1 -> { refreshMods(); go(Page.MODS, 0); setStatus("", DIM); }
                    case 2 -> { go(Page.SETTINGS, 0); setStatus("", DIM); }
                    default -> closeWindow();
                }
            }
            case SETTINGS -> {
                if (selected == 3) save();
                else if (selected == 4) back();
                else adjust(+1);
            }
            case MODS -> {
                if (selected == 0) { go(Page.BROWSE, 0); setStatus("", DIM); }
                else toggle(mods.get(selected - 1));
            }
            case BROWSE -> {
                if (selected == 0) keyboardOpen = true;
                else openDetail(hits.get(selected - 1));
            }
            case DETAIL -> {
                if (selected == 0 && plan != null && plan.installable()) install();
                else back();
            }
        }
    }

    private void back() {
        switch (page) {
            case SETTINGS -> {
                go(Page.HOME, 2);
                if (dirty) setStatus("Unsaved changes - open Settings and choose Save", WARN);
            }
            case MODS -> go(Page.HOME, 1);
            case BROWSE -> { refreshMods(); go(Page.MODS, 0); }
            case DETAIL -> { go(Page.BROWSE, 1 + Math.max(0, hits.indexOf(chosen))); plan = null; }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ home and settings

    private void adjust(int direction) {
        if (page != Page.SETTINGS) return;
        int step = direction * LauncherConfig.STEP_MIB;
        switch (selected) {
            case 0 -> {
                int heap = clamp(config.heapMib + step, LauncherConfig.MIN_HEAP_MIB, LauncherConfig.MAX_HEAP_MIB);
                if (heap + config.arenaMib <= LauncherConfig.MAX_TOTAL_MIB) config.heapMib = heap;
                else setStatus("Heap and GPU memory together are limited to " + LauncherConfig.MAX_TOTAL_MIB + " MiB", WARN);
            }
            case 1 -> {
                int arena = clamp(config.arenaMib + step, LauncherConfig.MIN_ARENA_MIB, LauncherConfig.MAX_ARENA_MIB);
                if (config.heapMib + arena <= LauncherConfig.MAX_TOTAL_MIB) config.arenaMib = arena;
                else setStatus("Heap and GPU memory together are limited to " + LauncherConfig.MAX_TOTAL_MIB + " MiB", WARN);
            }
            case 2 -> config.debugLog = !config.debugLog;
            default -> { return; }
        }
        dirty = true;
    }

    private void save() {
        try {
            config.save();
            dirty = false;
            setStatus("Saved. Applies when you press Play.", ACCENT);
        } catch (IOException failure) {
            setStatus("Saving failed: " + failure.getMessage(), ERROR);
        }
    }

    private void play() {
        try {
            if (dirty) config.save();
            config.writeNextStart();
        } catch (IOException failure) {
            setStatus("Cannot start: " + failure.getMessage(), ERROR);
            return;
        }
        setStatus("Starting Minecraft...", ACCENT);
        presentNow();
        int error = relaunch();
        try {
            Files.deleteIfExists(config.nextStart());
        } catch (IOException ignored) {
        }
        setStatus(String.format("Restart failed (0x%08x)", error), ERROR);
    }

    // ------------------------------------------------------------------ mods

    private void refreshMods() {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(config.modsDir())) {
            for (Path p : dir) {
                String n = p.getFileName().toString();
                if (n.endsWith(".jar") || n.endsWith(".jar.disabled")) found.add(p);
            }
        } catch (IOException missing) {
            // no mods yet
        }
        found.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        mods = found;
        if (selected > mods.size()) selected = mods.size();
    }

    private static boolean enabled(Path mod) {
        return mod.getFileName().toString().endsWith(".jar");
    }

    private void toggle(Path mod) {
        String n = mod.getFileName().toString();
        Path target = mod.resolveSibling(enabled(mod) ? n + ".disabled" : n.substring(0, n.length() - ".disabled".length()));
        try {
            Files.move(mod, target);
            setStatus((enabled(target) ? "Enabled " : "Disabled ") + target.getFileName()
                    + " - applies when you press Play.", ACCENT);
        } catch (IOException failure) {
            setStatus("Could not rename " + n + ": " + failure.getMessage(), ERROR);
        }
        refreshMods();
    }

    private void remove() {
        if (selected == 0 || selected > mods.size()) return;
        Path mod = mods.get(selected - 1);
        long now = System.nanoTime();
        if (!mod.equals(removeArmed) || now > removeArmedUntil) {
            removeArmed = mod;
            removeArmedUntil = now + 3_000_000_000L;
            setStatus("Press Triangle again to remove " + mod.getFileName(), WARN);
            return;
        }
        removeArmed = null;
        try {
            Files.delete(mod);
            setStatus("Removed " + mod.getFileName(), ACCENT);
        } catch (IOException failure) {
            setStatus("Could not remove " + mod.getFileName() + ": " + failure.getMessage(), ERROR);
        }
        refreshMods();
    }

    // ------------------------------------------------------------------ browse and install

    private void search() {
        keyboardOpen = false;
        String q = query.toString().trim();
        background("Searching Modrinth for \"" + q + "\"...", () -> modrinth.search(q), found -> {
            hits = found;
            go(Page.BROWSE, found.isEmpty() ? 0 : 1);
            setStatus(found.isEmpty() ? "No Fabric mods for " + config.gameVersion() + " match \"" + q + "\"."
                    : found.size() + " results.", DIM);
        });
    }

    private void openDetail(ModrinthClient.Hit hit) {
        chosen = hit;
        plan = null;
        go(Page.DETAIL, 0);
        background("Checking " + hit.title() + " for the console...", () -> installer.plan(hit.projectId(), hit.title()),
                result -> {
                    plan = result;
                    selected = 0;
                    setStatus(result.installable() ? "Ready to install." : "This cannot be installed.",
                            result.installable() ? ACCENT : ERROR);
                });
    }

    private void install() {
        ModInstaller.Plan current = plan;
        background("Installing " + current.entries().size() + " file(s)...", () -> {
            installer.install(current);
            return current.entries().size();
        }, count -> {
            refreshMods();
            plan = null;
            go(Page.MODS, 0);
            setStatus("Installed " + chosen.title() + (count > 1 ? " and " + (count - 1) + " dependenc"
                    + (count == 2 ? "y" : "ies") : "") + ". Applies when you press Play.", ACCENT);
        });
    }

    private void keyboard(Input.Action a) {
        switch (keys.apply(a, query)) {
            case SEARCH -> search();
            case CLOSE -> keyboardOpen = false;
            default -> { }
        }
    }

    private void setStatus(String text, int color) {
        status = text;
        statusColor = color;
    }

    // ------------------------------------------------------------------ drawing

    private void draw() {
        float s = r.s;
        r.text("ORBIS LAUNCHER", 120 * s, 70 * s, 6 * s, TEXT);
        r.text("Minecraft for PlayStation 4", 124 * s, 130 * s, 2.5f * s, DIM);
        switch (page) {
            case HOME -> drawHome(s);
            case SETTINGS -> drawSettings(s);
            case MODS -> drawMods(s);
            case BROWSE -> drawBrowse(s);
            case DETAIL -> drawDetail(s);
        }
        if (keyboardOpen) drawKeyboard(s);
        if (!status.isEmpty())
            r.text(r.fit(status, r.width - 240 * s, 3 * s), 120 * s, r.height - 150 * s, 3 * s, statusColor);
        r.text(hint(), 120 * s, r.height - 90 * s, 2.5f * s, DIM);
    }

    private String hint() {
        if (keyboardOpen) return "Cross: type   Square: delete   Options: search   Circle: close";
        return switch (page) {
            case MODS -> "Cross: open / enable-disable   Triangle (twice): remove   Circle: back";
            case BROWSE -> "Cross: search / details   Options: keyboard   Circle: back";
            default -> "Cross: select   Circle: back   D-pad: move / adjust";
        };
    }

    private void drawHome(float s) {
        r.rect(120 * s, 200 * s, 760 * s, 220 * s, PANEL);
        r.rect(120 * s, 200 * s, 8 * s, 220 * s, ACCENT);
        r.text("Minecraft " + config.gameVersion(), 160 * s, 230 * s, 4 * s, TEXT);
        r.text(config.modCount() + " mods  |  Fabric", 160 * s, 290 * s, 3 * s, DIM);
        r.text("Heap " + config.heapMib + " MiB  |  GPU " + config.arenaMib + " MiB", 160 * s, 340 * s, 3 * s, DIM);
        menu(List.of("Play", "Mods", "Settings", "Quit"), 120 * s, 480 * s, 760 * s);
        if (crash != null) drawCrash(s);
    }

    private void drawSettings(float s) {
        r.text("SETTINGS", 120 * s, 210 * s, 4 * s, TEXT);
        menu(List.of("Java heap        < " + config.heapMib + " MiB >",
                "GPU memory       < " + config.arenaMib + " MiB >",
                "Debug log        < " + (config.debugLog ? "on" : "off") + " >", "Save", "Back"),
                120 * s, 280 * s, 760 * s);
    }

    private void drawMods(float s) {
        long on = mods.stream().filter(LauncherScreen::enabled).count();
        r.text("MODS  (" + on + " enabled, " + (mods.size() - on) + " disabled)", 120 * s, 210 * s, 4 * s, TEXT);
        List<String> rows = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        rows.add("Browse Modrinth...");
        colors.add(ACCENT);
        for (Path mod : mods) {
            String n = mod.getFileName().toString();
            rows.add(enabled(mod) ? n.substring(0, n.length() - 4) : n.substring(0, n.length() - 13) + "   (disabled)");
            colors.add(enabled(mod) ? TEXT : DIM);
        }
        list(rows, colors, 120 * s, 280 * s, 1680 * s, 12);
    }

    private void drawBrowse(float s) {
        r.text("BROWSE MODRINTH  -  Fabric, " + config.gameVersion(), 120 * s, 210 * s, 4 * s, TEXT);
        List<String> rows = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        rows.add("Search: " + query + (System.nanoTime() / 500_000_000L % 2 == 0 ? "_" : " "));
        colors.add(ACCENT);
        for (ModrinthClient.Hit h : hits) {
            rows.add(h.title() + "  -  " + h.description());
            colors.add(TEXT);
        }
        list(rows, colors, 120 * s, 280 * s, 1680 * s, 12);
    }

    private void drawDetail(float s) {
        if (chosen == null) return;
        r.text(r.fit(chosen.title(), 1680 * s, 4 * s), 120 * s, 210 * s, 4 * s, TEXT);
        float y = 270 * s, scale = 2.5f * s, w = 1680 * s;
        for (String line : r.wrap(chosen.description() + "  (" + chosen.downloads() + " downloads, by "
                + chosen.author() + ")", w, scale)) {
            r.text(line, 120 * s, y, scale, DIM);
            y += 30 * s;
        }
        y += 20 * s;
        if (plan == null) {
            r.text(busy ? "Checking..." : "", 120 * s, y, 3 * s, DIM);
            return;
        }
        for (ModInstaller.Entry e : plan.entries()) {
            r.text("+ " + e.title() + "  " + e.version().versionNumber() + "  (" + e.version().file().filename() + ")",
                    120 * s, y, scale, TEXT);
            y += 32 * s;
        }
        for (String b : plan.blockers())
            for (String line : r.wrap("x " + b, w, scale)) { r.text(line, 120 * s, y, scale, ERROR); y += 30 * s; }
        for (String warning : plan.warnings())
            for (String line : r.wrap("! " + warning, w, scale)) { r.text(line, 120 * s, y, scale, WARN); y += 30 * s; }
        y += 20 * s;
        menu(plan.installable() ? List.of("Install", "Back") : List.of("Back"), 120 * s, y, 760 * s);
    }

    private void drawKeyboard(float s) {
        float x0 = 560 * s, y0 = 380 * s, key = 76 * s;
        r.rect(x0 - 30 * s, y0 - 90 * s, 10 * key + 60 * s, 5 * key + 130 * s, PANEL_HI);
        r.text(r.fit("Search: " + query + "_", 10 * key, 3 * s), x0, y0 - 60 * s, 3 * s, TEXT);
        String[] rows = OnScreenKeyboard.ROWS;
        for (int row = 0; row < rows.length; row++)
            for (int col = 0; col < rows[row].length(); col++) {
                boolean on = row == keys.row() && col == keys.col();
                float x = x0 + col * key, y = y0 + row * key;
                r.rect(x, y, key - 8 * s, key - 8 * s, on ? ACCENT : PANEL);
                char c = rows[row].charAt(col);
                r.text(c == ' ' ? "_" : String.valueOf(c), x + 26 * s, y + 20 * s, 3.5f * s, on ? DARK : TEXT);
            }
        String[] special = OnScreenKeyboard.SPECIAL;
        for (int i = 0; i < special.length; i++) {
            boolean on = keys.row() == OnScreenKeyboard.SPECIAL_ROW && keys.col() == i;
            float x = x0 + i * key * 10 / special.length, y = y0 + rows.length * key;
            r.rect(x, y, key * 10 / special.length - 8 * s, key - 8 * s, on ? ACCENT : PANEL);
            r.text(special[i], x + 30 * s, y + 22 * s, 3 * s, on ? DARK : TEXT);
        }
    }

    private void drawCrash(float s) {
        float x = 960 * s, y = 200 * s, w = 840 * s, pad = 30 * s, scale = 2.5f * s;
        List<String> lines = new ArrayList<>();
        for (String line : crash.lines()) lines.addAll(r.wrap(line, w - 2 * pad, scale));
        if (crash.source() != null) lines.addAll(r.wrap("Details: " + crash.source(), w - 2 * pad, scale));
        float h = 110 * s + lines.size() * 30 * s;
        r.rect(x, y, w, h, PANEL);
        r.rect(x, y, 8 * s, h, ERROR);
        r.text("LAST SESSION", x + pad, y + 24 * s, 2.5f * s, DIM);
        r.text(crash.title(), x + pad, y + 54 * s, 3 * s, ERROR);
        float ly = y + 100 * s;
        for (String line : lines) {
            r.text(line, x + pad, ly, scale, line.startsWith("Details: ") ? DIM : TEXT);
            ly += 30 * s;
        }
    }

    private void menu(List<String> items, float x, float y, float w) {
        float s = r.s;
        for (int i = 0; i < items.size(); i++) {
            boolean on = i == selected && !keyboardOpen;
            if (on) r.rect(x, y - 10 * s, w, 60 * s, ACCENT);
            r.text(items.get(i), x + 30 * s, y, 4 * s, on ? DARK : TEXT);
            y += 70 * s;
        }
    }

    /** A scrolling list of rows showing at most visible rows around the selection. */
    private void list(List<String> rows, List<Integer> colors, float x, float y, float w, int visible) {
        float s = r.s, scale = 3 * s;
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + visible) scroll = selected - visible + 1;
        for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
            boolean on = i == selected && !keyboardOpen;
            if (on) r.rect(x, y - 8 * s, w, 46 * s, ACCENT);
            r.text(r.fit(rows.get(i), w - 40 * s, scale), x + 20 * s, y, scale, on ? DARK : colors.get(i));
            y += 52 * s;
        }
        if (rows.size() > visible)
            r.text((scroll + 1) + "-" + Math.min(rows.size(), scroll + visible) + " of " + rows.size(),
                    x + w - 260 * s, y + 10 * s, 2.5f * s, DIM);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
