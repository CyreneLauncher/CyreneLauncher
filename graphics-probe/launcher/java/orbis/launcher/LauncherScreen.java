package orbis.launcher;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.opengl.GL;

import orbis.graphics.NativePlatform;

import static orbis.launcher.Renderer.*;
import static org.lwjgl.glfw.GLFW.*;

/**
 * The launcher's screen: the instance with Play, Mods, Settings and Quit; the installed mods; a
 * Modrinth browser for mods and modpacks with each project's description, gallery and versions,
 * whose installs are checked for the console first (ModInstaller, PackInstaller); and the accounts
 * with Microsoft sign-in by QR code. Controller, keyboard and mouse all work: the mouse through
 * regions each frame records (Clicks). Network and file work runs on background threads; its
 * results are applied on this, the GL thread.
 */
class LauncherScreen {
    private enum Page { INSTANCES, MODS, ACCOUNTS, SETTINGS, BROWSE, PROJECT, VERSIONS, CREATE }
    private enum Tab { DESCRIPTION, GALLERY, VERSIONS }
    private enum Zone { ACTIONS, TABS, CONTENT }
    private enum Focus { LIST, DETAIL }
    private enum Field { SEARCH, INSTANCE_NAME, JVM_ARGS, GAME_ARGS }
    private enum NameMode { CREATE, CLONE, RENAME }

    private final LauncherConfig config;
    private CrashAnalyzer.Report crash;
    private final Renderer r;
    private final Input input = new Input();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "launcher worker");
        t.setDaemon(true);
        return t;
    });
    /** Reads installed mods' metadata without holding up the main worker. */
    private final ExecutorService infoWorker = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "launcher mod info");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Clicks clicks = new Clicks();
    private final ImageCache images;
    private final Queue<Runnable> results = new ConcurrentLinkedQueue<>();
    private ModrinthClient modrinth;
    private final ModrinthClient suppliedModrinth;
    private ModInstaller installer;
    private final ModInstaller suppliedInstaller;

    private long window;
    private Page page = Page.INSTANCES;
    private Focus focus = Focus.LIST;
    private final int[] selections = new int[Page.values().length];
    private int selected;
    /** Each page's main list view, the project page's three views, and whether views follow the selection this frame. */
    private final Scroller[] scrollers = new Scroller[Page.values().length];
    private final Scroller descriptionScroll = new Scroller(), galleryScroll = new Scroller(), versionScroll = new Scroller();
    private boolean reveal = true;
    private long lastFrame;
    private float frameSeconds = 1f / 60;
    /** While rows of a scrolled list are drawn: the view their click regions are cut to. */
    private float[] hitClip;
    private String status = "";
    private int statusColor = DIM;
    private boolean dirty, busy;
    private int detailSelected;
    private NameMode nameMode;
    private String nameTargetId;
    private String pendingCloneName;
    private boolean cloneOptions, copyWorlds;
    private int cloneOptionSelected;
    private String instanceRemovePending;
    private InstanceStore.Operation instanceOperation;
    private long generation;
    private long activeTask;
    private boolean cancellable;

    // Versions page: choosing what a new instance runs.
    private VersionStore.Loader versionLoader = VersionStore.Loader.FABRIC;
    private List<VersionStore.GameVersion> catalog = List.of();
    private VersionStore.Loader catalogLoader;
    private VersionStore.GameVersion pendingVersion;
    private VersionStore.Loader pendingLoader;

    // Accounts page.
    private AccountStore accounts;
    private MicrosoftAuthClient.DeviceSession login;
    private boolean[][] loginQr;
    private boolean loginPolling;
    private String accountRemovePending;

    // Mods page.
    private List<Path> mods = new ArrayList<>();
    private final Map<Path, ModInspector.LocalMod> modInfo = new ConcurrentHashMap<>();
    private final Map<Path, Boolean> modInfoRequested = new ConcurrentHashMap<>();
    // Browse page: rows are the type tabs, the search box, the sort, the results, then "load more".
    private final StringBuilder query = new StringBuilder();
    private List<ModrinthClient.Hit> hits = List.of();
    private int totalHits;
    private boolean searched;
    private ModrinthClient.Type browseType = ModrinthClient.Type.MOD;
    private ModrinthClient.Sort sort = ModrinthClient.Sort.RELEVANCE;
    // Project page.
    private ModrinthClient.Project project;
    private List<Markdown.Block> description = List.of();
    private Tab tab = Tab.DESCRIPTION;
    private Zone zone = Zone.ACTIONS;
    private int actionSelected, gallerySelected, galleryOpen = -1, versionSelected;
    private float descriptionHeight, descriptionView;
    private List<ModrinthClient.VersionInfo> projectVersions;
    private boolean versionsAll;
    // Install dialogs: a mod's plan, or a read modpack; choice 0 installs, 1 cancels.
    private int dialogChoice;
    private String planTitle = "";
    private PackInstaller.Pack pack;
    private final TextEntry editor = new TextEntry();
    private Field editingField;
    private boolean systemKeyboardOpen;
    private final SystemKeyboard systemKeyboard = new SystemKeyboard();
    private Path removePending;
    private boolean removeConfirmed;
    private ModrinthClient.Hit chosen;
    private ModInstaller.Plan plan;

    LauncherScreen(LauncherConfig config) {
        this(config, new Renderer(), null, null);
    }

    /** Tests pass a renderer without GL and a Modrinth client and installer that do not use the network. */
    LauncherScreen(LauncherConfig config, Renderer renderer, ModrinthClient modrinth, ModInstaller installer) {
        this.config = config;
        this.crash = config.lastSession();
        this.r = renderer;
        this.suppliedModrinth = modrinth;
        this.modrinth = modrinth != null ? modrinth : newModrinth();
        this.suppliedInstaller = installer;
        String bundle = System.getProperty("runner.graphics.bundle");
        this.installer = installer != null ? installer
                : new ModInstaller(this.modrinth, config.modsDir(), bundle == null ? null : Path.of(bundle),
                        config.builtInVersions());
        List<InstanceStore.Instance> all = config.instances.list();
        for (int i = 0; i < all.size(); i++)
            if (all.get(i).id().equals(config.selectedInstance().id())) { selected = i; break; }
        if (!config.instances.recoveryWarning().isEmpty()) setStatus(config.instances.recoveryWarning(), WARN);
        for (int i = 0; i < scrollers.length; i++) scrollers[i] = new Scroller();
        ModrinthClient pictures = this.modrinth;
        this.images = new ImageCache(url -> pictures.fetchPublic(url), config.home.resolve("cache/images"));
    }

    /** A Modrinth client for the selected instance: its game version, and Quilt instances also take Fabric mods. */
    private ModrinthClient newModrinth() {
        return new ModrinthClient(config.gameVersion(), config.loader() == VersionStore.Loader.QUILT
                ? List.of("quilt", "fabric") : List.of("fabric"));
    }

    void run() throws IOException {
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(1920, 1080, "Cyrene Launcher", 0, 0);
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
                    input.takeTyped(); // Compatibility buffer; live input uses callback-order events.
                    applyEvents("", input.events());
                    applyMouse(input.mouse());
                    pollSystemKeyboard();
                    pollMicrosoftLogin();
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
        } finally {
            if (login != null) {
                try { authClient().cancelLogin(login); } catch (IOException ignored) { /* Nothing was saved. */ }
            }
            try {
                if (systemKeyboard.active()) {
                    requestSystemKeyboardCancel();
                    for (int attempt = 0; attempt < 16 && systemKeyboard.active(); attempt++) {
                        // The dialog needs time to close; the native side retries every 250 ms.
                        try { Thread.sleep(100); } catch (InterruptedException stop) { break; }
                        try { systemKeyboard.poll(); }
                        catch (RuntimeException failure) { break; }
                        if (systemKeyboard.active()) requestSystemKeyboardCancel();
                    }
                    if (systemKeyboard.active()) System.err.println("launcher: system keyboard remained open at shutdown");
                }
            } finally {
                glfwDestroyWindow(window);
                glfwTerminate();
            }
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------ background work

    private interface Work<T> { T run() throws Exception; }
    private interface Done<T> { void accept(T value); }

    private <T> void background(String message, Work<T> work, Done<T> done) {
        background(message, false, work, done);
    }

    /** Circle abandons a cancellable task: its result is dropped and the screen is usable again. */
    private <T> void background(String message, boolean canCancel, Work<T> work, Done<T> done) {
        busy = true;
        cancellable = canCancel;
        long started = generation, task = ++activeTask;
        setStatus(message, DIM);
        worker.execute(() -> {
            try {
                T value = work.run();
                results.add(() -> {
                    if (task == activeTask) busy = false;
                    if (task == activeTask && started == generation) done.accept(value);
                });
            } catch (Exception failure) {
                String text = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                results.add(() -> {
                    if (task == activeTask) busy = false;
                    if (task == activeTask && started == generation) setStatus("Failed: " + text, ERROR);
                });
            }
        });
    }

    private void cancelBackground() {
        activeTask++;
        generation++;
        busy = false;
        cancellable = false;
        setStatus("Cancelled.", WARN);
    }

    /** The account store, opened on first use; Play's worker shares it with this thread. */
    private synchronized AccountStore accounts() throws IOException {
        if (accounts == null) accounts = new AccountStore(config.home, config.runnerRoot);
        return accounts;
    }

    /** Downloading Minecraft needs a signed-in owner; says so instead of starting the download. */
    private boolean ownsMinecraft() {
        try {
            if (accounts().hasOwnedMinecraft()) return true;
        } catch (IOException failure) {
            // An unreadable account store proves no ownership.
        }
        setStatus(AccountStore.OWNERSHIP_REQUIRED, WARN);
        return false;
    }

    MicrosoftAuthClient authClient() throws IOException {
        return new MicrosoftAuthClient(accounts(), config.home);
    }

    // ------------------------------------------------------------------ input

    /** Legacy action-only entry point retained for callers that do not have source metadata. */
    void apply(String typed, List<Input.Action> actions) {
        applyEvents(typed, actions.stream().map(a -> new Input.Event(Input.Source.KEYBOARD, a)).toList());
    }

    /** The live loop always supplies the physical source of every action. */
    void applyEvents(String typed, List<Input.Event> events) {
        // Controller, keyboard and clicks move the selection, and views follow it; hovering and the wheel never do.
        if (!events.isEmpty()) reveal = true;
        if (editor.active() && !systemKeyboardOpen && !typed.isEmpty()) editor.insert(typed);
        for (Input.Event event : events) {
            if (event.action() == null) {
                if (editor.active() && !systemKeyboardOpen) editor.insert(event.text());
                continue;
            }
            Input.Action action = event.action();
            lastInput = event.source();
            if (systemKeyboardOpen) {
                if (action == Input.Action.BACK) { cancelSystemKeyboard(); break; }
                continue;
            }
            if (removePending != null) {
                modalAction(action);
                if (removePending == null) break;
                continue;
            }
            if (instanceRemovePending != null) {
                instanceRemoveAction(action);
                if (instanceRemovePending == null) break;
                continue;
            }
            if (accountRemovePending != null) {
                accountRemoveAction(action);
                if (accountRemovePending == null) break;
                continue;
            }
            if (login != null) {
                if (action == Input.Action.BACK) { cancelMicrosoftLogin(); break; }
                continue;
            }
            if (cloneOptions) {
                cloneOptionAction(action);
                if (!cloneOptions) break;
                continue;
            }
            if (plan != null && !busy) {
                planAction(action);
                if (plan == null) break;
                continue;
            }
            if (pack != null && !busy) {
                packAction(action);
                if (pack == null) break;
                continue;
            }
            if (editor.active()) {
                if (action == Input.Action.CONFIRM) { finishEdit(true); break; }
                if (action == Input.Action.BACK) { finishEdit(false); break; }
                editor.edit(action);
                continue;
            }
            if (busy && instanceOperation != null && action == Input.Action.BACK) {
                instanceOperation.cancel();
                generation++;
                setStatus("Cancelling...", WARN);
                continue;
            }
            if (busy && cancellable && action == Input.Action.BACK) {
                cancelBackground();
                break;
            }
            if (!busy) {
                act(event);
                if (editor.active() || systemKeyboardOpen || removePending != null
                        || instanceRemovePending != null || accountRemovePending != null || cloneOptions
                        || login != null || plan != null || pack != null) break;
            }
        }
    }

    private void beginEdit(Field field, Input.Source source) {
        editingField = field;
        InstanceStore.Instance current = config.selectedInstance();
        String initial = field == Field.SEARCH ? query.toString()
                : field == Field.JVM_ARGS ? current.jvmArgs()
                : field == Field.GAME_ARGS ? current.gameArgs()
                : nameMode == NameMode.RENAME ? config.instances.get(nameTargetId).name()
                : nameMode == NameMode.CLONE ? config.instances.get(nameTargetId).name() + " Copy"
                : pendingVersion != null ? pendingLoader.title + " " + pendingVersion.id()
                : "New Minecraft";
        editor.begin(initial);
        input.releaseBarrier();
        if (source == Input.Source.CONTROLLER) {
            systemKeyboardOpen = field == Field.SEARCH ? beginSystemKeyboard("Search mods", 100)
                    : field == Field.JVM_ARGS ? beginSystemKeyboard("JVM arguments", InstanceArguments.MAX_LENGTH)
                    : field == Field.GAME_ARGS ? beginSystemKeyboard("Minecraft arguments", InstanceArguments.MAX_LENGTH)
                    : beginSystemKeyboard("Instance name", 80);
            if (!systemKeyboardOpen) setStatus("System keyboard unavailable. Use a physical keyboard to edit.", WARN);
        }
    }

    /** Native keyboard lifecycle stays behind these hooks so a failed dialog keeps inline editing usable. */
    protected boolean beginSystemKeyboard(String title, int maxLength) {
        try {
            return systemKeyboard.begin(editor.value(), title, maxLength);
        } catch (RuntimeException failure) {
            setStatus("System keyboard unavailable: " + failure.getMessage() + ". Use a physical keyboard.", WARN);
            return false;
        }
    }

    protected void pollSystemKeyboard() {
        if (!systemKeyboardOpen) return;
        try {
            int state = systemKeyboard.poll();
            if (state == 2) completeSystemKeyboard(systemKeyboard.result(), true);
            else if (state == 3 || state == 0) completeSystemKeyboard(null, false);
        } catch (RuntimeException failure) {
            boolean cancelling = systemKeyboard.cancelling();
            if (systemKeyboard.active()) {
                requestSystemKeyboardCancel();
                setStatus("System keyboard failed; closing dialog: " + failure.getMessage(), ERROR);
            } else {
                systemKeyboardOpen = false;
                if (cancelling) finishEdit(false);
                else {
                    setStatus("System keyboard failed: " + failure.getMessage() + ". Use a physical keyboard.", ERROR);
                    input.releaseBarrier();
                }
            }
        }
    }

    protected void cancelSystemKeyboard() {
        if (requestSystemKeyboardCancel()) {
            systemKeyboardOpen = false;
            finishEdit(false);
        } else {
            setStatus("Closing system keyboard...", DIM);
        }
    }

    private boolean requestSystemKeyboardCancel() {
        try {
            return systemKeyboard.cancel();
        } catch (RuntimeException failure) {
            System.err.println("launcher: system keyboard cleanup failed: " + failure);
            return !systemKeyboard.active();
        }
    }

    protected void completeSystemKeyboard(String value, boolean accepted) {
        systemKeyboardOpen = false;
        if (accepted) {
            editor.begin("");
            editor.insert(value);
        }
        finishEdit(accepted);
    }

    private void finishEdit(boolean accepted) {
        if (!editor.active()) return;
        String value = accepted ? editor.accept() : editor.cancel();
        if (editingField == Field.SEARCH) {
            query.setLength(0);
            query.append(value);
            if (accepted) search(true);
        } else if (editingField == Field.JVM_ARGS || editingField == Field.GAME_ARGS) {
            if (accepted) {
                InstanceStore.Instance current = config.selectedInstance();
                boolean jvm = editingField == Field.JVM_ARGS;
                try {
                    config.instances.saveArguments(current.id(), jvm ? value : current.jvmArgs(), jvm ? current.gameArgs() : value);
                    setStatus((jvm ? "JVM" : "Minecraft") + " arguments saved for " + current.name() + ".", GOOD);
                } catch (IOException failure) {
                    setStatus(failure.getMessage(), ERROR);
                }
            }
        } else if (accepted) {
            try {
                if (nameMode == NameMode.RENAME) {
                    config.instances.rename(nameTargetId, value);
                    setStatus("Instance renamed.", GOOD);
                } else if (nameMode == NameMode.CREATE) {
                    VersionStore.GameVersion version = pendingVersion;
                    VersionStore.Loader loader = pendingLoader;
                    pendingVersion = null;
                    go(Page.INSTANCES, -1);
                    startInstanceOperation("Creating instance...", new InstanceStore.Operation(),
                            op -> config.instances.create(value, profileFor(version, loader, op), op));
                } else {
                    pendingCloneName = value;
                    cloneOptions = true;
                    copyWorlds = false;
                    cloneOptionSelected = 0;
                }
            } catch (IOException | IllegalArgumentException failure) {
                setStatus("Cannot save name: " + failure.getMessage(), ERROR);
            }
        }
        editingField = null;
        input.releaseBarrier();
    }

    /** Applies finished background work; returns whether some is still running. */
    boolean applyResults() {
        for (Runnable result; (result = results.poll()) != null; ) result.run();
        return busy;
    }

    void frame(int framebufferWidth, int framebufferHeight) {
        long now = System.nanoTime();
        frameSeconds = lastFrame == 0 ? 1f / 60 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        r.begin(framebufferWidth, framebufferHeight);
        images.update(r);
        clicks.begin();
        try {
            draw();
        } finally {
            clicks.end();
            r.unclip();
        }
        r.end();
    }

    // ------------------------------------------------------------------ pointer

    /**
     * The mouse: moving over something selects it, a left click activates it (the same as Cross or
     * Enter on it), a right click goes back, the wheel scrolls the view under it (smoothly, without
     * selecting anything). Everything a click does goes through applyEvents, so busy work and dialogs gate
     * the mouse exactly as they gate the controller.
     */
    void applyMouse(List<Input.Mouse> events) {
        for (Input.Mouse m : events) {
            // Pointing alone does not switch the hints; a click or the wheel does.
            if (m.kind() != Input.MouseKind.MOVE) lastInput = Input.Source.MOUSE;
            switch (m.kind()) {
                case MOVE -> { if (!busy && !systemKeyboardOpen) clicks.hover(m.x(), m.y()); }
                case PRESS -> {
                    if (m.button() == 1) press(Input.Action.BACK);
                    else if (systemKeyboardOpen) { /* the dialog has the input */ }
                    else if (busy) { if (cancellable || instanceOperation != null) clicks.click(m.x(), m.y()); }
                    else clicks.click(m.x(), m.y());
                }
                case WHEEL -> {
                    // The wheel only moves the view under the pointer; it never selects anything.
                    if (!systemKeyboardOpen) clicks.scroll(m.x(), m.y(), m.amount());
                }
                case RELEASE -> { }
            }
        }
    }

    /** One action, as if pressed: what clicks and the wheel turn into. */
    private void press(Input.Action action) {
        applyEvents("", List.of(new Input.Event(Input.Source.MOUSE, action)));
    }

    /** A clickable rectangle: hovering runs select, clicking runs select then action (null: none). */
    private void hit(float x, float y, float w, float h, Runnable select, Input.Action action) {
        if (hitClip != null) {
            float x1 = Math.max(x, hitClip[0]), y1 = Math.max(y, hitClip[1]);
            float x2 = Math.min(x + w, hitClip[0] + hitClip[2]), y2 = Math.min(y + h, hitClip[1] + hitClip[3]);
            if (x2 <= x1 || y2 <= y1) return;
            x = x1; y = y1; w = x2 - x1; h = y2 - y1;
        }
        clicks.add(x, y, w, h, select, action == null ? null : () -> press(action));
    }

    private interface Row { void draw(int index, float y); }

    /**
     * A list of equal rows in a view, drawn at its scroller's smooth offset and clipped to the view;
     * the wheel over it scrolls it. selectedRow (or -1) is revealed only after a controller, keyboard
     * or click move, so hovering a half-visible row never pulls the list.
     */
    private void scrollList(Scroller sc, int count, int selectedRow, float x, float y, float w, float h, float rowH, Row row) {
        float content = count * rowH;
        if (reveal && selectedRow >= 0 && selectedRow < count) sc.reveal(selectedRow * rowH, (selectedRow + 1) * rowH, h);
        float offset = sc.update(content, h, frameSeconds);
        clicks.wheel(x, y, w, h, amount -> sc.wheel(amount, 120 * r.s));
        r.clip(x, y, w, h);
        hitClip = new float[]{x, y, w, h};
        try {
            int first = Math.max(0, (int) (offset / rowH)), last = Math.min(count - 1, (int) ((offset + h) / rowH));
            for (int i = first; i <= last; i++) row.draw(i, y + i * rowH - offset);
        } finally {
            hitClip = null;
            r.unclip();
        }
        scrollbar(sc, content, x + w + 6 * r.s, y, h);
    }

    private void scrollbar(Scroller sc, float content, float x, float y, float h) {
        if (content <= h + 1) return;
        float s = r.s, barH = Math.max(40 * s, h * h / content);
        r.roundRect(x, y, 5 * s, h, 12 * s, PANEL);
        r.rect(x, y + (h - barH) * (sc.offset() / Math.max(1, sc.max())), 5 * s, barH, DIM);
    }

    /** No dialog, edit or work in progress: a click may change the page directly. */
    private boolean idle() {
        return !busy && !editor.active() && !systemKeyboardOpen && removePending == null && instanceRemovePending == null
                && accountRemovePending == null && login == null && !cloneOptions && plan == null && pack == null
                && galleryOpen < 0;
    }

    /** An unexpected error in a frame: logged with its stack (launcher.log) and shown, not fatal. */
    void internalError(Throwable failure) {
        System.err.println("launcher: internal error on page " + page);
        failure.printStackTrace();
        busy = false;
        if (systemKeyboardOpen && !requestSystemKeyboardCancel()) {
            setStatus("Closing system keyboard after error...", ERROR);
            return;
        }
        systemKeyboardOpen = false;
        if (editor.active()) editor.cancel();
        selected = 0;
        scrollers[page.ordinal()].reset();
        setStatus("Internal error: " + failure + " (details in launcher.log)", ERROR);
    }

    // Platform effects, replaced in tests.
    void presentNow() {
        frame(r.width, r.height);
        glfwSwapBuffers(window);
    }

    int relaunch(String intent) {
        return NativePlatform.nRelaunchWithIntent(intent);
    }

    void showLaunchError(String message) { setStatus(message, ERROR); }

    /** For tests: what is selected and on which page, which the wheel must never change. */
    String selectionState() { return page + "/" + selected + "/" + focus + "/" + zone + "/" + tab + "/" + versionSelected + "/" + gallerySelected; }

    void closeWindow() {
        glfwSetWindowShouldClose(window, true);
    }

    private void act(Input.Event event) {
        Input.Action a = event.action();
        if (a == Input.Action.PREV_SECTION || a == Input.Action.NEXT_SECTION) {
            switchSection(a == Input.Action.NEXT_SECTION ? 1 : -1);
            return;
        }
        if (a == Input.Action.FOCUS) {
            focus = focus == Focus.LIST ? Focus.DETAIL : Focus.LIST;
            return;
        }
        if (a == Input.Action.BACK) { back(); return; }
        if (page == Page.INSTANCES) {
            instanceAction(a, event.source());
            return;
        }
        if (page == Page.BROWSE) {
            browseAction(a, event.source());
            return;
        }
        if (page == Page.PROJECT) {
            projectAction(a);
            return;
        }
        int count = items();
        // Lists may shrink underneath a remembered selection (cleared results, a signed-out account).
        if (selected >= count) selected = Math.max(0, count - 1);
        switch (a) {
            case UP -> { if (count > 0) selected = (selected + count - 1) % count; }
            case DOWN -> { if (count > 0) selected = (selected + 1) % count; }
            case LEFT -> adjust(-1);
            case RIGHT -> adjust(+1);
            case CONFIRM -> confirm(event.source());
            case ALT2 -> {
                if (page == Page.MODS) remove();
                else if (page == Page.ACCOUNTS) manageAccount(event.source());
            }
            case MENU -> { if (page == Page.BROWSE) beginEdit(Field.SEARCH, event.source()); }
            default -> { }
        }
        selections[page.ordinal()] = selected;
    }

    private int items() {
        return switch (page) {
            case INSTANCES -> config.instances.list().size();
            case VERSIONS -> 1 + catalog.size();
            case SETTINGS -> SETTINGS_ROWS;
            case CREATE -> 2;
            case ACCOUNTS -> accountList().size() + 2;
            case MODS -> 1 + mods.size();
            case BROWSE -> browseRows();
            case PROJECT -> 1;
        };
    }

    private void go(Page next, int select) {
        selections[page.ordinal()] = selected;
        page = next;
        selected = select < 0 ? selections[next.ordinal()] : select;
        if (select >= 0) scrollers[next.ordinal()].reset();
        focus = Focus.LIST;
        reveal = true;
    }

    private void switchSection(int direction) {
        Page next = SECTIONS[(section() + direction + SECTIONS.length) % SECTIONS.length];
        go(next, -1);
        setStatus("", DIM);
    }

    private void confirm(Input.Source source) {
        switch (page) {
            case INSTANCES -> instanceAction(Input.Action.CONFIRM, source);
            case ACCOUNTS -> {
                List<AccountStore.Account> list = accountList();
                if (selected < list.size()) makeDefault(list.get(selected));
                else if (selected == list.size()) startMicrosoftLogin();
                else cycleInstanceAccount(+1);
            }
            case SETTINGS -> {
                switch (selected) {
                    case 2 -> beginEdit(Field.JVM_ARGS, source);
                    case 3 -> beginEdit(Field.GAME_ARGS, source);
                    case 5 -> save();
                    case 6 -> back();
                    default -> adjust(+1);
                }
            }
            case CREATE -> {
                if (selected == 0) openVersions();
                else openBrowse(ModrinthClient.Type.MODPACK);
            }
            case MODS -> {
                if (selected == 0) openBrowse(config.loader() == VersionStore.Loader.VANILLA
                        ? ModrinthClient.Type.MODPACK : ModrinthClient.Type.MOD);
                else toggle(mods.get(selected - 1));
            }
            case VERSIONS -> {
                if (selected == 0) cycleLoader(+1);
                else if (catalogLoader == versionLoader && selected - 1 < catalog.size()) {
                    VersionStore.GameVersion version = catalog.get(selected - 1);
                    if (installedFor(version, versionLoader) == null && !builtIn(version, versionLoader)
                            && !ownsMinecraft()) return;
                    pendingVersion = version;
                    pendingLoader = versionLoader;
                    nameMode = NameMode.CREATE;
                    nameTargetId = null;
                    beginEdit(Field.INSTANCE_NAME, source);
                }
            }
        }
    }

    private void back() {
        switch (page) {
            case INSTANCES -> focus = Focus.LIST;
            case SETTINGS -> {
                go(Page.INSTANCES, -1);
                if (dirty) setStatus("Unsaved changes - open Instance settings and choose Save", WARN);
            }
            case ACCOUNTS -> go(Page.INSTANCES, -1);
            case MODS -> go(Page.INSTANCES, -1);
            case BROWSE -> {
                go(browseFrom, -1);
                if (browseFrom == Page.MODS) refreshMods();
            }
            case VERSIONS, CREATE -> { go(Page.INSTANCES, -1); setStatus("", DIM); }
            case PROJECT -> {
                if (galleryOpen >= 0) galleryOpen = -1;
                else { go(Page.BROWSE, -1); setStatus("", DIM); }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ home and settings

    private void instanceAction(Input.Action action, Input.Source source) {
        List<InstanceStore.Instance> values = config.instances.list();
        // A click acts on the last frame's rows; the list may have shrunk since.
        if (selected >= values.size()) selected = Math.max(0, values.size() - 1);
        if (focus == Focus.LIST) {
            switch (action) {
                case UP -> selected = (selected + values.size() - 1) % values.size();
                case DOWN -> selected = (selected + 1) % values.size();
                case RIGHT -> focus = Focus.DETAIL;
                case CONFIRM -> {
                    try { chooseInstance(values.get(selected).id()); }
                    catch (IOException failure) { setStatus("Cannot select: " + failure.getMessage(), ERROR); }
                }
                default -> { }
            }
            selections[page.ordinal()] = selected;
            return;
        }
        switch (action) {
            case LEFT -> focus = Focus.LIST;
            case UP -> detailSelected = (detailSelected + HOME_ACTIONS.size() - 1) % HOME_ACTIONS.size();
            case DOWN -> detailSelected = (detailSelected + 1) % HOME_ACTIONS.size();
            case CONFIRM -> {
                InstanceStore.Instance current = config.selectedInstance();
                switch (detailSelected) {
                    case 0 -> play();
                    case 1 -> { go(Page.MODS, -1); refreshMods(); setStatus("", DIM); }
                    case 2 -> { go(Page.SETTINGS, 0); setStatus("", DIM); }
                    case 3 -> { go(Page.CREATE, 0); setStatus("", DIM); }
                    case 4 -> { nameMode = NameMode.CLONE; nameTargetId = current.id(); beginEdit(Field.INSTANCE_NAME, source); }
                    case 5 -> { nameMode = NameMode.RENAME; nameTargetId = current.id(); beginEdit(Field.INSTANCE_NAME, source); }
                    case 6 -> {
                        if (config.instances.list().size() <= 1) setStatus("Keep at least one instance.", WARN);
                        else { instanceRemovePending = current.id(); removeConfirmed = false; }
                    }
                    default -> closeWindow();
                }
            }
            default -> { }
        }
    }

    private static final List<String> HOME_ACTIONS = List.of("Play", "Mods", "Instance settings", "New instance",
            "Clone", "Rename", "Remove", "Quit");
    /** Heap, GPU memory, JVM arguments, Minecraft arguments, debug log, Save, Back. */
    private static final int SETTINGS_ROWS = 7;
    private Page browseFrom = Page.MODS;
    /** The device used last; the hints name its buttons or keys. */
    private Input.Source lastInput = Input.Source.CONTROLLER;

    private void chooseInstance(String id) throws IOException {
        if (dirty) config.save();
        generation++;
        config.selectInstance(id);
        dirty = false;
        resyncSelected();
        setStatus("Selected " + config.selectedInstance().name(), GOOD);
    }

    /** Refreshes every cached instance-scoped screen object after a store transition. */
    private void resyncSelected() {
        config.refreshSelected();
        List<InstanceStore.Instance> values = config.instances.list();
        for (int i = 0; i < values.size(); i++)
            if (values.get(i).id().equals(config.selectedInstance().id())) { selected = i; break; }
        crash = config.lastSession();
        if (suppliedModrinth == null) modrinth = newModrinth();
        String bundle = System.getProperty("runner.graphics.bundle");
        installer = suppliedInstaller != null ? suppliedInstaller
                : new ModInstaller(modrinth, config.modsDir(), bundle == null ? null : Path.of(bundle),
                        config.builtInVersions());
        mods = new ArrayList<>();
        hits = List.of();
        totalHits = 0;
        searched = false;
        plan = null;
        pack = null;
        project = null;
        projectVersions = null;
    }

    private interface InstanceWork { InstanceStore.Instance run(InstanceStore.Operation operation) throws IOException; }

    private void startInstanceOperation(String message, InstanceStore.Operation operation, InstanceWork work) {
        long started = ++generation;
        instanceOperation = operation;
        busy = true;
        setStatus(message, DIM);
        worker.execute(() -> {
            try {
                InstanceStore.Instance result = work.run(operation);
                results.add(() -> {
                    if (instanceOperation == operation) { instanceOperation = null; busy = false; }
                    if (started != generation) {
                        setStatus("Operation cancelled. Check the instance list for any completed copy.", WARN);
                        return;
                    }
                    try { chooseInstance(result.id()); }
                    catch (IOException failure) { setStatus("Created, but could not select: " + failure.getMessage(), ERROR); }
                });
            } catch (Exception failure) {
                results.add(() -> {
                    if (instanceOperation == operation) { instanceOperation = null; busy = false; }
                    if (started != generation) { setStatus("Operation cancelled.", WARN); return; }
                    setStatus(operation.cancelled() ? "Operation cancelled." : "Instance operation failed: " + failure.getMessage(),
                            operation.cancelled() ? WARN : ERROR);
                });
            }
        });
    }

    private void cloneOptionAction(Input.Action action) {
        switch (action) {
            case UP -> cloneOptionSelected = (cloneOptionSelected + 2) % 3;
            case DOWN -> cloneOptionSelected = (cloneOptionSelected + 1) % 3;
            case LEFT, RIGHT -> { if (cloneOptionSelected == 0) copyWorlds = !copyWorlds; }
            case BACK -> { cloneOptions = false; pendingCloneName = null; input.releaseBarrier(); }
            case CONFIRM -> {
                if (cloneOptionSelected == 0) copyWorlds = !copyWorlds;
                else if (cloneOptionSelected == 1) {
                    String sourceId = nameTargetId, name = pendingCloneName;
                    boolean worlds = copyWorlds;
                    cloneOptions = false;
                    startInstanceOperation("Cloning instance...", new InstanceStore.Operation(),
                            op -> config.instances.cloneInstance(sourceId, name, worlds, op));
                    input.releaseBarrier();
                } else { cloneOptions = false; pendingCloneName = null; input.releaseBarrier(); }
            }
            default -> { }
        }
    }

    private void instanceRemoveAction(Input.Action action) {
        switch (action) {
            case LEFT, UP -> removeConfirmed = false;
            case RIGHT, DOWN -> removeConfirmed = true;
            case BACK -> { instanceRemovePending = null; input.releaseBarrier(); }
            case CONFIRM -> {
                String id = instanceRemovePending;
                instanceRemovePending = null;
                InstanceStore.Instance target = config.instances.get(id);
                if (removeConfirmed && target == null) setStatus("That instance is already gone.", WARN);
                else if (removeConfirmed) {
                    boolean managed = !target.imported();
                    if (dirty) {
                        try { config.save(); dirty = false; }
                        catch (IOException failure) {
                            setStatus("Cannot save settings before removal: " + failure.getMessage(), ERROR);
                            input.releaseBarrier();
                            return;
                        }
                    }
                    dirty = false;
                    busy = true;
                    setStatus("Removing instance...", DIM);
                    worker.execute(() -> {
                        Exception error = null;
                        try { config.instances.remove(id, managed); }
                        catch (Exception failure) { error = failure; }
                        Exception result = error;
                        results.add(() -> {
                            busy = false;
                            resyncSelected();
                            if (result != null) setStatus("Could not remove instance: " + result.getMessage(), ERROR);
                            else if (!config.instances.recoveryWarning().isEmpty())
                                setStatus(config.instances.recoveryWarning(), WARN);
                            else setStatus(managed ? "Instance and its files deleted."
                                    : "Imported registration removed; game files kept.", GOOD);
                        });
                    });
                }
                input.releaseBarrier();
            }
            default -> { }
        }
    }

    private void adjust(int direction) {
        if (page == Page.VERSIONS && selected == 0) { cycleLoader(direction); return; }
        if (page == Page.ACCOUNTS && selected == accountList().size() + 1) { cycleInstanceAccount(direction); return; }
        if (page == Page.CREATE) { selected = (selected + 2 + direction) % 2; return; }
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
            case 4 -> config.debugLog = !config.debugLog;
            default -> { return; }
        }
        dirty = true;
    }

    private void save() {
        try {
            config.save();
            dirty = false;
            setStatus("Saved. Applies when you press Play.", GOOD);
        } catch (IOException failure) {
            setStatus("Saving failed: " + failure.getMessage(), ERROR);
        }
    }

    private void play() {
        final InstanceStore.Instance instance = config.selectedInstance();
        background("Checking account before starting...", () -> {
            if (dirty) config.save();
            AccountStore.Account account = accounts().resolveAccount(instance.accountId());
            authClient().refresh(account.id());
            InstanceStore.Instance current = config.instances.get(instance.id());
            VersionStore.Installed version = config.installed(current);
            if (version != null) prepareRuntime(version.java());
            return new LaunchPreparation(config).prepare(current, account.id());
        }, request -> {
            setStatus("Starting Minecraft...", GOOD);
            presentNow();
            int error = relaunch(request.preparationId());
            try { new LaunchPreparation(config).rollback(); }
            catch (IOException recovery) { setStatus("Restart failed; runner settings require recovery", ERROR); return; }
            setStatus(String.format("Restart failed (0x%08x)", error), ERROR);
        });
    }

    // ------------------------------------------------------------------ versions

    private void openVersions() {
        go(Page.VERSIONS, 0);
        if (catalogLoader != versionLoader) loadCatalog();
        else setStatus("Choose what the new instance runs.", DIM);
    }

    private void cycleLoader(int direction) {
        VersionStore.Loader[] all = VersionStore.Loader.values();
        versionLoader = all[(versionLoader.ordinal() + direction + all.length) % all.length];
        selected = 0;
        loadCatalog();
    }

    private void loadCatalog() {
        VersionStore.Loader loader = versionLoader;
        catalog = List.of();
        catalogLoader = null;
        background("Loading Minecraft versions for " + loader.title + "...", true, () -> fetchCatalog(loader), found -> {
            if (loader != versionLoader) return;
            catalog = found;
            catalogLoader = loader;
            setStatus(found.size() + " versions. 1.14 to 1.16 use older OpenGL and are less tested.", DIM);
        });
    }

    /** Network seams, replaced in tests. */
    protected List<VersionStore.GameVersion> fetchCatalog(VersionStore.Loader loader) throws IOException {
        return config.versions.catalog(loader);
    }

    protected VersionStore.Installed installVersion(VersionStore.GameVersion version, VersionStore.Loader loader,
                                                    InstanceStore.Operation op) throws IOException {
        VersionStore.Installed installed = config.versions.install(version, loader, op);
        config.ensureRuntime(installed.java(), op::progress);
        return installed;
    }

    /** Before a launch: the runtime the version needs, downloaded or updated, with progress in the status line. */
    protected void prepareRuntime(int java) throws IOException {
        config.ensureRuntime(java, text -> results.add(() -> setStatus(text, DIM)));
    }

    /** The game profile a new instance runs: the built-in 1.18.2, an installed version, or a fresh download. */
    private Path profileFor(VersionStore.GameVersion version, VersionStore.Loader loader, InstanceStore.Operation op)
            throws IOException {
        if (version == null || builtIn(version, loader)) return config.metadata;
        VersionStore.Installed present = installedFor(version, loader);
        if (present != null) return present.gameProfile();
        return installVersion(version, loader, op).gameProfile();
    }

    private boolean builtIn(VersionStore.GameVersion version, VersionStore.Loader loader) {
        return loader == VersionStore.Loader.FABRIC && version.id().equals(config.builtInGameVersion());
    }

    private VersionStore.Installed installedFor(VersionStore.GameVersion version, VersionStore.Loader loader) {
        for (VersionStore.Installed installed : config.versions.list())
            if (installed.minecraft().equals(version.id()) && installed.loader() == loader) return installed;
        return null;
    }

    // ------------------------------------------------------------------ accounts

    private List<AccountStore.Account> accountList() {
        try { return accounts().list(); }
        catch (IOException failure) { return List.of(); }
    }

    private String accountName(String id) {
        if (id == null || id.isEmpty()) return "Add your own account";
        try {
            AccountStore.Account account = accounts().get(id);
            return account == null ? "Add your own account" : account.username();
        } catch (IOException failure) { return "Unavailable"; }
    }

    /** What Play will use for the selected instance. */
    private String effectiveAccount(InstanceStore.Instance instance) {
        if (!instance.accountId().isEmpty()) return accountName(instance.accountId());
        try { return "Default: " + accountName(accounts().defaultAccountId()); }
        catch (IOException failure) { return "Unavailable"; }
    }

    private void makeDefault(AccountStore.Account account) {
        try {
            accounts().selectDefault(account.id());
            setStatus(account.username() + " is now the default account.", GOOD);
        } catch (IOException failure) { setStatus("Cannot change the default: " + failure.getMessage(), ERROR); }
    }

    private void manageAccount(Input.Source source) {
        List<AccountStore.Account> list = accountList();
        if (selected >= list.size()) return;
        AccountStore.Account account = list.get(selected);
        accountRemovePending = account.id();
        removeConfirmed = false;
    }

    /** Follow default, then each account in turn; the choice is saved with the instance. */
    private void cycleInstanceAccount(int direction) {
        List<String> choices = new ArrayList<>();
        choices.add("");
        for (AccountStore.Account account : accountList()) choices.add(account.id());
        int at = Math.max(0, choices.indexOf(config.selectedInstance().accountId()));
        String next = choices.get((at + direction + choices.size()) % choices.size());
        try {
            config.setInstanceAccount(next);
            setStatus(config.selectedInstance().name() + " uses " + effectiveAccount(config.selectedInstance()) + ".", GOOD);
        } catch (IOException failure) { setStatus("Cannot save the instance account: " + failure.getMessage(), ERROR); }
    }

    private void accountRemoveAction(Input.Action action) {
        switch (action) {
            case LEFT, UP -> removeConfirmed = false;
            case RIGHT, DOWN -> removeConfirmed = true;
            case BACK -> { accountRemovePending = null; input.releaseBarrier(); }
            case CONFIRM -> {
                String id = accountRemovePending;
                accountRemovePending = null;
                if (removeConfirmed) {
                    String name = accountName(id);
                    try {
                        accounts().remove(id);
                        setStatus("Signed out " + name + "; its saved sign-in was deleted.", GOOD);
                    } catch (IOException failure) { setStatus("Sign-out failed: " + failure.getMessage(), ERROR); }
                    selected = Math.min(selected, items() - 1);
                }
                input.releaseBarrier();
            }
            default -> { }
        }
    }

    private record LoginStart(MicrosoftAuthClient.DeviceSession session, boolean[][] qr) { }

    private void startMicrosoftLogin() {
        background("Contacting Microsoft...", () -> {
            MicrosoftAuthClient.DeviceSession session = authClient().beginLogin();
            return new LoginStart(session, LoginQr.encode(session.verificationUri()));
        }, start -> {
            login = start.session();
            loginQr = start.qr();
            setStatus("Waiting for sign-in on your phone or computer...", DIM);
        });
    }

    /** Polls Microsoft no sooner than the server's interval; one poll at a time. */
    protected void pollMicrosoftLogin() {
        MicrosoftAuthClient.DeviceSession session = login;
        if (session == null || loginPolling) return;
        Instant now = Instant.now();
        if (now.isBefore(session.nextPollAt()) && now.isBefore(session.expiresAt())) return;
        loginPolling = true;
        System.err.println("launcher: sign-in poll scheduled");
        worker.execute(() -> {
            AccountStore.Session signedIn = null;
            Exception error = null;
            try { signedIn = authClient().pollLogin(session); }
            catch (Exception failure) { error = failure; }
            catch (Throwable unexpected) {
                System.err.println("launcher: sign-in failed unexpectedly");
                unexpected.printStackTrace();
                error = new IOException("Unexpected error: " + unexpected);
            }
            AccountStore.Session result = signedIn;
            Exception failed = error;
            results.add(() -> {
                loginPolling = false;
                if (login != session) return;
                if (failed == null && result == null) return;
                System.err.println("launcher: sign-in ended: " + (failed == null ? "signed in"
                        : failed instanceof MicrosoftAuthClient.AuthException auth ? auth.category() + " at " + auth.stage()
                        : failed.getClass().getSimpleName()));
                login = null;
                loginQr = null;
                if (failed == null) setStatus("Signed in as " + result.username() + ".", GOOD);
                else if (!(failed instanceof CancellationException))
                    setStatus("Sign-in failed: " + failed.getMessage(), ERROR);
                input.releaseBarrier();
            });
        });
    }

    private void cancelMicrosoftLogin() {
        MicrosoftAuthClient.DeviceSession session = login;
        login = null;
        loginQr = null;
        try { authClient().cancelLogin(session); } catch (IOException ignored) { /* Nothing was saved. */ }
        setStatus("Sign-in cancelled.", WARN);
        input.releaseBarrier();
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
        // Name, version, description and icon of each JAR, read once in the background.
        for (Path mod : found) {
            Path key = canonicalMod(mod);
            if (modInfoRequested.putIfAbsent(key, Boolean.TRUE) != null) continue;
            infoWorker.execute(() -> {
                ModInspector.LocalMod info = ModInspector.describe(mod);
                if (info != null) modInfo.put(key, info);
            });
        }
    }

    /** The same key for a mod whether it is enabled or not. */
    private static Path canonicalMod(Path mod) {
        String n = mod.getFileName().toString();
        return n.endsWith(".disabled") ? mod.resolveSibling(n.substring(0, n.length() - ".disabled".length())) : mod;
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
                    + " - applies when you press Play.", GOOD);
        } catch (IOException failure) {
            setStatus("Could not rename " + n + ": " + failure.getMessage(), ERROR);
        }
        refreshMods();
    }

    private void remove() {
        if (selected == 0 || selected > mods.size()) return;
        removePending = mods.get(selected - 1);
        removeConfirmed = false;
    }

    private void modalAction(Input.Action action) {
        switch (action) {
            case LEFT, UP -> removeConfirmed = false;
            case RIGHT, DOWN -> removeConfirmed = true;
            case BACK -> { removePending = null; input.releaseBarrier(); }
            case CONFIRM -> {
                Path target = removePending;
                removePending = null;
                if (removeConfirmed) {
                    try {
                        Files.delete(target);
                        setStatus("Removed " + target.getFileName(), GOOD);
                    } catch (IOException failure) {
                        setStatus("Could not remove " + target.getFileName() + ": " + failure.getMessage(), ERROR);
                    }
                    refreshMods();
                }
                input.releaseBarrier();
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ browse

    private void openBrowse(ModrinthClient.Type type) {
        boolean changed = type != browseType;
        browseType = type;
        if (type == ModrinthClient.Type.MOD && config.loader() == VersionStore.Loader.VANILLA) {
            browseType = ModrinthClient.Type.MODPACK;
            setStatus("Vanilla instances do not load mods; modpacks create their own instance.", WARN);
        } else setStatus("", DIM);
        if (page != Page.BROWSE && page != Page.PROJECT) browseFrom = page == Page.MODS ? Page.MODS : Page.CREATE;
        go(Page.BROWSE, -1);
        if (!searched || changed) search(false);
    }

    private int browseRows() {
        return 3 + hits.size() + (hits.size() < totalHits ? 1 : 0);
    }

    private void browseAction(Input.Action a, Input.Source source) {
        int count = browseRows();
        if (selected >= count) selected = count - 1;
        switch (a) {
            case UP -> selected = (selected + count - 1) % count;
            case DOWN -> selected = (selected + 1) % count;
            case LEFT, RIGHT -> {
                int d = a == Input.Action.RIGHT ? 1 : -1;
                if (selected == 0) switchType(d);
                else if (selected == 2) cycleSort(d);
            }
            case CONFIRM -> {
                if (selected == 0) switchType(1);
                else if (selected == 1) beginEdit(Field.SEARCH, source);
                else if (selected == 2) cycleSort(1);
                else if (selected - 3 < hits.size()) openProject(hits.get(selected - 3));
                else loadMore();
            }
            case MENU -> beginEdit(Field.SEARCH, source);
            default -> { }
        }
        selections[page.ordinal()] = selected;
    }

    private void switchType(int direction) {
        ModrinthClient.Type[] all = ModrinthClient.Type.values();
        ModrinthClient.Type next = all[(browseType.ordinal() + direction + all.length) % all.length];
        if (next == ModrinthClient.Type.MOD && config.loader() == VersionStore.Loader.VANILLA) {
            setStatus("Vanilla instances do not load mods. Create a Fabric or Quilt instance, or get a modpack.", WARN);
            return;
        }
        browseType = next;
        search(false);
    }

    private void cycleSort(int direction) {
        ModrinthClient.Sort[] all = ModrinthClient.Sort.values();
        sort = all[(sort.ordinal() + direction + all.length) % all.length];
        search(false);
    }

    private void search(boolean fromEdit) {
        String q = query.toString().trim();
        ModrinthClient.Type type = browseType;
        ModrinthClient.Sort order = sort;
        ModrinthClient client = modrinth;
        searched = true;
        background(q.isEmpty() ? "Loading " + type.title.toLowerCase(java.util.Locale.ROOT) + "..."
                : "Searching Modrinth for \"" + q + "\"...", true, () -> client.search(q, type, order, 0), found -> {
            hits = found.hits();
            totalHits = found.total();
            if (page == Page.BROWSE && (fromEdit || selected > 2)) selected = found.hits().isEmpty() ? 1 : 3;
            scrollers[Page.BROWSE.ordinal()].reset();
            setStatus(found.hits().isEmpty() ? "Nothing on Modrinth matches \"" + q + "\" for " + target(type) + "."
                    : String.format("%,d results for %s.", found.total(), target(type)), DIM);
        });
    }

    private void loadMore() {
        String q = query.toString().trim();
        ModrinthClient.Type type = browseType;
        ModrinthClient.Sort order = sort;
        ModrinthClient client = modrinth;
        int offset = hits.size();
        background("Loading more...", true, () -> client.search(q, type, order, offset), found -> {
            List<ModrinthClient.Hit> merged = new ArrayList<>(hits);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (ModrinthClient.Hit h : merged) seen.add(h.projectId());
            for (ModrinthClient.Hit h : found.hits()) if (seen.add(h.projectId())) merged.add(h);
            // A page that adds nothing new ends the list, so "load more" cannot loop.
            totalHits = merged.size() == hits.size() ? merged.size() : found.total();
            hits = merged;
            setStatus(String.format("%,d of %,d results.", hits.size(), totalHits), DIM);
        });
    }

    /** What the browser searches for, for messages. */
    private String target(ModrinthClient.Type type) {
        return type == ModrinthClient.Type.MOD
                ? config.loader().title + " " + config.gameVersion() : "Fabric and Quilt, Minecraft 1.14 to " + VersionStore.newest();
    }

    // ------------------------------------------------------------------ project

    private record ProjectView(ModrinthClient.Project project, List<Markdown.Block> blocks) {}

    private void openProject(ModrinthClient.Hit hit) {
        chosen = hit;
        project = null;
        description = List.of();
        projectVersions = null;
        versionsAll = false;
        tab = Tab.DESCRIPTION;
        zone = Zone.ACTIONS;
        actionSelected = 0;
        gallerySelected = 0;
        galleryOpen = -1;
        versionSelected = 0;
        descriptionScroll.reset();
        galleryScroll.reset();
        versionScroll.reset();
        go(Page.PROJECT, 0);
        ModrinthClient client = modrinth;
        background("Loading " + hit.title() + "...", true, () -> {
            ModrinthClient.Project p = client.project(hit.projectId());
            return new ProjectView(p, Markdown.parse(p.body()));
        }, view -> {
            if (chosen != hit) return;
            project = view.project();
            description = view.blocks();
            setStatus("", DIM);
        });
    }

    private ModrinthClient.Type projectType() {
        return project != null ? project.type() : chosen != null ? chosen.type() : ModrinthClient.Type.MOD;
    }

    private void projectAction(Input.Action a) {
        if (galleryOpen >= 0) {
            int n = project == null ? 0 : project.gallery().size();
            switch (a) {
                case LEFT, UP -> { if (n > 0) galleryOpen = (galleryOpen + n - 1) % n; }
                case RIGHT, DOWN -> { if (n > 0) galleryOpen = (galleryOpen + 1) % n; }
                case CONFIRM -> galleryOpen = -1;
                default -> { }
            }
            return;
        }
        switch (zone) {
            case ACTIONS -> {
                switch (a) {
                    case LEFT, RIGHT -> actionSelected = 1 - actionSelected;
                    case DOWN -> zone = Zone.TABS;
                    case CONFIRM -> { if (actionSelected == 0) installLatest(); else back(); }
                    default -> { }
                }
            }
            case TABS -> {
                switch (a) {
                    case LEFT -> selectTab(Tab.values()[(tab.ordinal() + 2) % 3]);
                    case RIGHT -> selectTab(Tab.values()[(tab.ordinal() + 1) % 3]);
                    case UP -> zone = Zone.ACTIONS;
                    case DOWN, CONFIRM -> zone = Zone.CONTENT;
                    default -> { }
                }
            }
            case CONTENT -> contentAction(a);
        }
    }

    private void selectTab(Tab next) {
        tab = next;
        if (tab == Tab.VERSIONS && projectVersions == null) loadVersions();
    }

    private void contentAction(Input.Action a) {
        switch (tab) {
            case DESCRIPTION -> {
                switch (a) {
                    case UP -> {
                        if (descriptionScroll.atTop()) zone = Zone.TABS;
                        else descriptionScroll.by(-90 * r.s);
                    }
                    case DOWN -> descriptionScroll.by(90 * r.s);
                    case LEFT -> descriptionScroll.by(-descriptionView * 0.9f);
                    case RIGHT -> descriptionScroll.by(descriptionView * 0.9f);
                    default -> { }
                }
            }
            case GALLERY -> {
                int n = project == null ? 0 : project.gallery().size();
                if (n == 0) { if (a == Input.Action.UP) zone = Zone.TABS; return; }
                switch (a) {
                    case LEFT -> gallerySelected = Math.max(0, gallerySelected - 1);
                    case RIGHT -> gallerySelected = Math.min(n - 1, gallerySelected + 1);
                    case UP -> { if (gallerySelected < GALLERY_COLUMNS) zone = Zone.TABS; else gallerySelected -= GALLERY_COLUMNS; }
                    case DOWN -> gallerySelected = Math.min(n - 1, gallerySelected + GALLERY_COLUMNS);
                    case CONFIRM -> galleryOpen = Math.min(gallerySelected, n - 1);
                    default -> { }
                }
            }
            case VERSIONS -> {
                int rows = 1 + (projectVersions == null ? 0 : projectVersions.size());
                if (versionSelected >= rows) versionSelected = rows - 1;
                switch (a) {
                    case UP -> { if (versionSelected == 0) zone = Zone.TABS; else versionSelected--; }
                    case DOWN -> versionSelected = Math.min(rows - 1, versionSelected + 1);
                    case LEFT, RIGHT -> { if (versionSelected == 0) toggleAllVersions(); }
                    case CONFIRM -> {
                        if (versionSelected == 0) toggleAllVersions();
                        else installVersion(projectVersions.get(versionSelected - 1));
                    }
                    default -> { }
                }
            }
        }
    }

    private void toggleAllVersions() {
        versionsAll = !versionsAll;
        versionSelected = 0;
        loadVersions();
    }

    private void loadVersions() {
        ModrinthClient.Hit hit = chosen;
        ModrinthClient.Type type = projectType();
        boolean all = versionsAll;
        ModrinthClient client = modrinth;
        projectVersions = null;
        background("Loading versions...", true, () -> client.projectVersions(hit.projectId(), type, !all), found -> {
            if (chosen != hit || versionsAll != all) return;
            projectVersions = found;
            versionSelected = Math.min(versionSelected, found.size());
            setStatus(found.isEmpty() ? (all ? "No versions published." : "No version for " + target(type)
                    + ". Choose \"All versions\" to see the rest.") : found.size() + " versions.", found.isEmpty() ? WARN : DIM);
        });
    }

    /** Install: the newest version that fits (mods), or the newest compatible pack version. */
    private void installLatest() {
        if (chosen == null) return;
        if (projectType() == ModrinthClient.Type.MOD) planMod(null);
        else installPack(null);
    }

    private void installVersion(ModrinthClient.VersionInfo version) {
        if (projectType() == ModrinthClient.Type.MOD) planMod(version);
        else installPack(version);
    }

    // ------------------------------------------------------------------ mod install

    private void planMod(ModrinthClient.VersionInfo version) {
        if (config.loader() == VersionStore.Loader.VANILLA) {
            setStatus("Vanilla instances do not load mods. Create a Fabric or Quilt instance first.", WARN);
            return;
        }
        ModrinthClient.Hit hit = chosen;
        ModInstaller target = installer;
        String title = hit.title();
        background("Downloading and checking " + title + (version != null ? " " + version.number() : "") + "...", true,
                () -> target.plan(hit.projectId(), title, version == null ? null : version.version()),
                result -> {
                    plan = result;
                    planTitle = title + (version != null ? " " + version.number() : "");
                    dialogChoice = result.installable() ? 0 : 1;
                    setStatus(result.installable() ? "Ready to install." : "This cannot be installed.",
                            result.installable() ? GOOD : ERROR);
                    input.releaseBarrier();
                });
    }

    private void planAction(Input.Action action) {
        switch (action) {
            case LEFT, UP -> dialogChoice = plan.installable() ? 0 : 1;
            case RIGHT, DOWN -> dialogChoice = 1;
            case BACK -> { plan = null; input.releaseBarrier(); }
            case CONFIRM -> {
                if (dialogChoice == 0 && plan.installable()) install();
                else { plan = null; setStatus("", DIM); }
                input.releaseBarrier();
            }
            default -> { }
        }
    }

    private void install() {
        ModInstaller.Plan current = plan;
        ModInstaller target = installer;
        String into = config.selectedInstance().name(), title = planTitle;
        plan = null;
        background("Installing " + current.entries().size() + " file(s) into " + into + "...", () -> {
            target.install(current);
            return current.entries().size();
        }, count -> {
            refreshMods();
            setStatus("Installed " + title + (count > 1 ? " and " + (count - 1) + " dependenc"
                    + (count == 2 ? "y" : "ies") : "") + " into " + into + ". Applies when you press Play.", GOOD);
        });
    }

    // ------------------------------------------------------------------ modpack install

    /** Downloads the chosen pack version (or the newest compatible one) and shows what installing it means. */
    private void installPack(ModrinthClient.VersionInfo version) {
        if (!ownsMinecraft()) return;
        ModrinthClient.Hit hit = chosen;
        ModrinthClient client = modrinth;
        PackInstaller packs = packInstaller();
        Path folder = config.home.resolve("cache/packs");
        background("Downloading modpack " + hit.title() + "...", true, () -> {
            ModrinthClient.VersionInfo v = version;
            if (v == null) {
                List<ModrinthClient.VersionInfo> compatible = client.projectVersions(hit.projectId(), ModrinthClient.Type.MODPACK, true);
                if (compatible.isEmpty()) throw new IOException("no version of this pack runs Fabric or Quilt on Minecraft 1.14 to "
                        + VersionStore.newest());
                v = compatible.get(0);
            }
            return packs.download(v.file(), folder, new InstanceStore.Operation());
        }, read -> {
            pack = read;
            dialogChoice = read.installable() ? 0 : 1;
            setStatus(read.installable() ? "Ready to create the instance." : "This pack cannot be installed.",
                    read.installable() ? GOOD : ERROR);
            input.releaseBarrier();
        });
    }

    private void packAction(Input.Action action) {
        switch (action) {
            case LEFT, UP -> dialogChoice = pack.installable() ? 0 : 1;
            case RIGHT, DOWN -> dialogChoice = 1;
            case BACK -> closePack();
            case CONFIRM -> {
                if (dialogChoice == 0 && pack.installable()) startPackInstall(pack);
                else closePack();
            }
            default -> { }
        }
    }

    private void closePack() {
        PackInstaller.Pack closing = pack;
        pack = null;
        deleteArchive(closing);
        setStatus("", DIM);
        input.releaseBarrier();
    }

    private static void deleteArchive(PackInstaller.Pack p) {
        if (p == null) return;
        try { Files.deleteIfExists(p.archive()); } catch (IOException ignored) { /* the cache is pruned later */ }
    }

    /** Creates the instance in the background, like a clone: cancellable, with progress, selected when done. */
    private void startPackInstall(PackInstaller.Pack chosenPack) {
        pack = null;
        input.releaseBarrier();
        InstanceStore.Operation operation = new InstanceStore.Operation();
        PackInstaller packs = packInstaller();
        long started = ++generation;
        instanceOperation = operation;
        busy = true;
        setStatus("Installing modpack " + chosenPack.name() + "...", DIM);
        worker.execute(() -> {
            PackInstaller.Installed done = null;
            Exception error = null;
            try { done = packs.install(chosenPack, chosenPack.name(), operation); }
            catch (Exception failure) { error = failure; }
            finally { deleteArchive(chosenPack); }
            PackInstaller.Installed result = done;
            Exception failed = error;
            results.add(() -> {
                if (instanceOperation == operation) { instanceOperation = null; busy = false; }
                if (failed != null || result == null) {
                    setStatus(operation.cancelled() || failed instanceof CancellationException ? "Modpack install cancelled."
                            : "Modpack install failed: " + (failed == null ? "unknown" : failed.getMessage()),
                            operation.cancelled() || failed instanceof CancellationException ? WARN : ERROR);
                    return;
                }
                if (started == generation) {
                    try { chooseInstance(result.instance().id()); }
                    catch (IOException failure) { setStatus("Installed, but could not select: " + failure.getMessage(), ERROR); return; }
                    go(Page.INSTANCES, -1);
                }
                setStatus("Installed " + chosenPack.name() + " as a new instance." + (result.disabled().isEmpty() ? ""
                        : " Disabled (native code): " + String.join(", ", result.disabled()) + "."),
                        result.disabled().isEmpty() ? GOOD : WARN);
            });
        });
    }

    /** Seams for tests: where pack files come from, and the profile a pack's version runs. */
    protected byte[] fetchPackFile(String url, long max) throws IOException {
        return VersionStore.fetch(url, max);
    }

    PackInstaller packInstaller() {
        return new PackInstaller(this::fetchPackFile, this::packProfile, config.instances);
    }

    /** An installed profile for the pack's Minecraft and loader, no older than its loader; else a fresh install. */
    private Path packProfile(String minecraft, VersionStore.Loader loader, String loaderVersion, InstanceStore.Operation op)
            throws IOException {
        int[] wanted = VersionStore.parts(loaderVersion);
        if (loader == VersionStore.Loader.FABRIC && minecraft.equals(config.builtInGameVersion())
                && !config.loaderVersion.isEmpty() && VersionStore.compare(VersionStore.parts(config.loaderVersion), wanted) >= 0)
            return config.metadata;
        for (VersionStore.Installed installed : config.versions.list())
            if (installed.minecraft().equals(minecraft) && installed.loader() == loader
                    && VersionStore.compare(VersionStore.parts(installed.loaderVersion()), wanted) >= 0)
                return installed.gameProfile();
        op.progress("Finding Minecraft " + minecraft + "...");
        for (VersionStore.GameVersion version : fetchCatalog(loader))
            if (version.id().equals(minecraft)) {
                VersionStore.Installed fresh = installVersion(version, loader, op);
                if (VersionStore.compare(VersionStore.parts(fresh.loaderVersion()), wanted) < 0)
                    throw new IOException("the pack needs " + loader.title + " loader " + loaderVersion + "; the newest is "
                            + fresh.loaderVersion());
                return fresh.gameProfile();
            }
        throw new IOException(loader.title + " does not offer Minecraft " + minecraft);
    }

    private void setStatus(String text, int color) {
        status = text;
        statusColor = color;
    }

    // ------------------------------------------------------------------ drawing

    private void draw() {
        float s = r.s;
        drawHeader(s);
        switch (page) {
            case INSTANCES -> drawHome(s);
            case ACCOUNTS -> drawAccounts(s);
            case SETTINGS -> drawSettings(s);
            case CREATE -> drawCreate(s);
            case MODS -> drawMods(s);
            case BROWSE -> drawBrowse(s);
            case PROJECT -> drawProject(s);
            case VERSIONS -> drawVersions(s);
        }
        r.unclip();

        if (removePending != null) drawRemoveDialog(s);
        if (instanceRemovePending != null) drawInstanceRemoveDialog(s);
        if (accountRemovePending != null) drawAccountRemoveDialog(s);
        if (login != null) drawLoginDialog(s);
        if (cloneOptions) drawCloneOptions(s);
        if (plan != null) drawPlanDialog(s);
        if (pack != null) drawPackDialog(s);
        if (editor.active() && editingField != null && editingField != Field.SEARCH) drawNameDialog(s);
        String shownStatus = instanceOperation == null ? status : instanceOperation.progress();
        if (instanceOperation != null && instanceOperation.destination() != null)
            r.text(r.fit("Destination: " + instanceOperation.destination(), r.width - 240 * s, 2.2f * s),
                    120 * s, r.height - 178 * s, 2.2f * s, DIM);
        if (!shownStatus.isEmpty())
            r.text(r.fit(shownStatus, r.width - 240 * s, 2.8f * s), 120 * s, r.height - 140 * s, 2.8f * s, statusColor);
        // The bottom bar: the controls of this page, as the PS4 shows its buttons.
        r.rect(0, r.height - 86 * s, r.width, 86 * s, BAR);
        r.rect(0, r.height - 86 * s, r.width, 2 * s, LINE);
        float hintWidth = drawHint(hint(), 120 * s, r.height - 62 * s, 38 * s);
        // Clicking the hint line while something can be cancelled cancels it.
        if (busy && (cancellable || instanceOperation != null))
            hit(120 * s, r.height - 70 * s, hintWidth, 54 * s, null, Input.Action.BACK);
        reveal = false;
    }

    /**
     * A hint line ("Cross/Enter: select   Circle: back") with each key drawn as its controller button.
     * Returns its width.
     */
    private float drawHint(String hint, float x, float y, float size) {
        float s = r.s, start = x, scale = 2.4f * s;
        for (String part : hint.split(" {2,}")) {
            int colon = part.indexOf(": ");
            if (colon < 0) {
                r.textMiddle(part, x, y, size, scale, DIM);
                x += r.width(part, scale) + 34 * s;
                continue;
            }
            for (String key : buttons(part.substring(0, colon))) x += r.button(key, x, y, size) + 6 * s;
            x += 6 * s;
            String label = part.substring(colon + 2);
            r.textMiddle(label, x, y, size, scale, DIM);
            x += r.width(label, scale) + 34 * s;
        }
        return x - start;
    }

    /**
     * What a hint's keys are on the device used last: on a controller "Cross/Enter" is the cross and
     * "L1/R1" both shoulder buttons; on a keyboard and mouse they are Enter and Page Up/Down, and actions
     * only a controller button reaches by itself are clicks.
     */
    private List<String> buttons(String keys) {
        String first = keys.split(" or ")[0];
        String lead = first.split("/")[0];
        boolean pad = lastInput == Input.Source.CONTROLLER;
        return switch (lead) {
            case "Cross" -> List.of(pad ? "cross" : "ENTER");
            case "Circle" -> List.of(pad ? "circle" : "ESC");
            case "Triangle" -> List.of(pad ? "triangle" : "CLICK");
            case "Square" -> List.of(pad ? "square" : "CLICK");
            case "L1" -> pad ? List.of("L1", "R1") : List.of("PG UP", "PG DN");
            case "Start" -> List.of(pad ? "OPTIONS" : "CLICK");
            case "Left" -> List.of("LEFT / RIGHT");
            case "Up" -> List.of("UP / DOWN");
            default -> List.of(first.toUpperCase(java.util.Locale.ROOT));
        };
    }

    /**
     * The runner's orbis.console.mode as shown: a PS4, a PS4 Pro in Pro mode, or a PS4 Pro in base mode, where
     * the console's Boost Mode setting applies (the system reports no Boost Mode state of its own).
     */
    static String[] consoleMode(String value) {
        return switch (value) {
            case "pro" -> new String[]{"PS4 Pro  \u00b7  Pro mode", "pro"};
            case "pro-base" -> new String[]{"PS4 Pro  \u00b7  Base mode, Boost Mode if enabled", "pro-base"};
            case "base" -> new String[]{"PS4  \u00b7  Base mode", "base"};
            default -> new String[]{"Console mode not reported", ""};
        };
    }

    /** The top bar: the application, the sections between L1 and R1, and who plays what. */
    private void drawHeader(float s) {
        r.rect(0, 0, r.width, 130 * s, BAR);
        r.rect(0, 130 * s, r.width, 2 * s, LINE);
        float logo = 78 * s;
        if (r.artwork() > 0) {
            // The art with rounded corners inside a thin rounded rim, as the PS4 shows application icons.
            r.roundImage(r.artwork(), 60 * s, 26 * s, logo, logo, 16 * s, BAR);
            r.roundFrame(60 * s, 26 * s, logo, logo, 16 * s, LINE);
        } else r.roundRect(60 * s, 26 * s, logo, logo, 14 * s, ACCENT);
        r.textMiddle("Cyrene Launcher", 160 * s, 26 * s, 46 * s, 3.6f * s, TEXT);
        // The console and the mode it runs the launcher in, as the runner detected them.
        String[] mode = consoleMode(System.getProperty("orbis.console.mode", ""));
        int modeColor = mode[1].equals("pro") ? ACCENT_HI : mode[1].equals("pro-base") ? WARN : DIM;
        r.disc(162 * s, 79 * s, 14 * s, modeColor);
        r.textMiddle(mode[0], 186 * s, 72 * s, 28 * s, 2.1f * s, mode[1].isEmpty() ? DIM : TEXT);
        drawSections(s);
        InstanceStore.Instance active = config.selectedInstance();
        String account = effectiveAccount(active);
        float right = r.width - 60 * s, avatar = 62 * s;
        float textW = Math.max(r.width(r.fit(account, 420 * s, 2.7f * s), 2.7f * s),
                r.width(r.fit(active.name(), 420 * s, 2.1f * s), 2.1f * s));
        float chipX = right - textW - avatar - 50 * s;
        r.roundRect(chipX, 26 * s, right - chipX, 80 * s, 40 * s, PANEL_HI);
        r.disc(chipX + 9 * s, 35 * s, avatar, ACCENT);
        String initial = account.isEmpty() ? "?" : account.substring(account.lastIndexOf(' ') + 1, account.lastIndexOf(' ') + 2)
                .toUpperCase(java.util.Locale.ROOT);
        r.textCentered(initial, chipX + 9 * s, 35 * s, avatar, avatar, 3.4f * s, TEXT);
        r.textMiddle(r.fit(account, 420 * s, 2.7f * s), chipX + avatar + 26 * s, 34 * s, 34 * s, 2.7f * s, TEXT);
        r.textMiddle(r.fit(active.name(), 420 * s, 2.1f * s), chipX + avatar + 26 * s, 68 * s, 28 * s, 2.1f * s, DIM);
    }

    /** Mods and settings belong to an instance, so they open from it rather than from a tab. */
    private static final Page[] SECTIONS = {Page.INSTANCES, Page.ACCOUNTS};
    private static final String[] SECTION_TITLES = {"Instances", "Accounts"};

    private int section() {
        return page == Page.ACCOUNTS ? 1 : 0;
    }

    /** The sections as tabs between the shoulder buttons that switch them; clicking one opens it. */
    private void drawSections(float s) {
        float scale = 2.6f * s, pad = 30 * s, gap = 8 * s, h = 58 * s, y = 36 * s;
        float total = 0;
        for (String title : SECTION_TITLES) total += r.width(title, scale) + 2 * pad + gap;
        float x = (r.width - total) / 2 - 20 * s;
        x += r.button("L1", x - 70 * s, y + (h - 38 * s) / 2, 38 * s) - 70 * s + 20 * s;
        int current = section();
        for (int i = 0; i < SECTIONS.length; i++) {
            float w = r.width(SECTION_TITLES[i], scale) + 2 * pad;
            if (i == current) r.roundRect(x, y, w, h, h / 2, ACCENT);
            r.textMiddle(SECTION_TITLES[i], x + pad, y, h, scale, i == current ? TEXT : DIM);
            int index = i;
            clicks.add(x, y, w, h, null, () -> {
                if (!idle()) return;
                go(SECTIONS[index], -1);
                setStatus("", DIM);
            });
            x += w + gap;
        }
        r.button("R1", x + 12 * s, y + (h - 38 * s) / 2, 38 * s);
    }

    private String hint() {
        if (removePending != null) return "Left/Right: choice   Cross/Enter: accept   Circle/Escape: cancel";
        if (instanceRemovePending != null) return "Left/Right: choice   Cross/Enter: accept   Circle/Escape: cancel";
        if (accountRemovePending != null) return "Left/Right: choice   Cross/Enter: accept   Circle/Escape: cancel";
        if (plan != null || pack != null) return "Left/Right: choice   Cross/Enter: accept   Circle/Escape: cancel";
        if (login != null) return "Circle/Escape: cancel sign-in";
        if (busy && cancellable) return "Circle/Escape or click here: cancel";
        if (cloneOptions) return "Up/Down: choice   Cross/Enter: toggle or continue   Circle/Escape: cancel";
        if (instanceOperation != null) return "Working   Circle/Escape or click here: cancel";
        if (systemKeyboardOpen) return systemKeyboard.cancelling()
                ? "Closing system keyboard..." : "System keyboard: confirm or cancel";
        if (editor.active()) return "Type: edit   Arrows/Home/End: caret   Enter: accept   Escape: cancel";
        return switch (page) {
            case MODS -> "Cross: open / enable-disable   Triangle: remove   Circle: back";
            case BROWSE -> "Cross: open   Left/Right: tabs and sort   Start: search   Circle: back";
            case PROJECT -> galleryOpen >= 0 ? "Left/Right: previous / next image   Circle: close"
                    : "Up/Down: buttons, tabs, content   Left/Right: choose   Cross: select   Circle: back";
            case VERSIONS -> "Left/Right: loader   Cross/Enter: choose version   Circle/Escape: back";
            case CREATE -> "Left/Right: choose   Cross/Enter: open   Circle/Escape: back";
            case SETTINGS -> "Left/Right: change   Cross/Enter: edit or select   Circle/Escape: back";
            case INSTANCES -> "Cross/Enter: select   Left/Right or Tab: focus   L1/R1: section";
            case ACCOUNTS -> "Cross: make default / choose   Triangle: rename or sign out   Circle: back   L1/R1: section";
            default -> "Cross/Enter: select   Circle/Escape: back   L1/R1: section";
        };
    }

    private void drawHome(float s) {
        r.text("Instances", 120 * s, 168 * s, 3.6f * s, TEXT);
        List<InstanceStore.Instance> values = config.instances.list();
        if (selected >= values.size()) selected = values.size() - 1;
        float lx = 120 * s, lw = 750 * s, rowH = 96 * s;
        scrollList(scrollers[Page.INSTANCES.ordinal()], values.size(), focus == Focus.LIST ? selected : -1,
                lx, 230 * s, lw, 690 * s, rowH, (i, y) -> {
            InstanceStore.Instance value = values.get(i);
            boolean highlighted = focus == Focus.LIST && selected == i && !editor.active();
            float h = rowH - 12 * s, tile = 60 * s;
            row(lx, y, lw, h, highlighted);
            String[] info = instanceInfo(value);
            r.roundRect(lx + 22 * s, y + (h - tile) / 2, tile, tile, 12 * s, loaderColor(info[1]));
            r.textCentered(info[1], lx + 22 * s, y + (h - tile) / 2, tile, tile, 3.2f * s, TEXT);
            // Name and version as one block centred on the row.
            r.textMiddle(r.fit(value.name(), lw - 280 * s, 2.9f * s), lx + 104 * s, y + 10 * s, 34 * s, 2.9f * s, TEXT);
            r.textMiddle(r.fit(info[0], lw - 280 * s, 2.1f * s), lx + 104 * s, y + 44 * s, 26 * s, 2.1f * s, DIM);
            if (value.id().equals(config.selectedInstance().id())) {
                String label = "SELECTED";
                float bw = r.width(label, 1.8f * s) + 28 * s, bh = 30 * s;
                r.roundRect(lx + lw - bw - 22 * s, y + (h - bh) / 2, bw, bh, bh / 2, ACCENT_DARK);
                r.textCentered(label, lx + lw - bw - 22 * s, y + (h - bh) / 2, bw, bh, 1.8f * s, TEXT);
            }
            hit(lx, y, lw, rowH - 12 * s, () -> { focus = Focus.LIST; selected = i; }, Input.Action.CONFIRM);
        });

        // The selected instance: its card under the application art, then Play and the other actions.
        InstanceStore.Instance active = config.selectedInstance();
        float x = 930 * s, w = 870 * s, top = 168 * s, banner = 150 * s, cardH = 300 * s;
        r.roundRect(x, top, w, cardH, 18 * s, PANEL);
        if (r.artwork() > 0) {
            r.image(r.artwork(), x, top, w, banner, 0, 0.30f, 1, 0.30f + (banner / w), WHITE);
            r.roundCorners(x, top, w, banner, 18 * s, BACKGROUND, true, false);
            // Fade the art into the card: whole-pixel bands, so none overlap into darker lines.
            int bands = 32;
            for (int band = 0; band < bands; band++) {
                float y0 = Math.round(top + banner * band / bands), y1 = Math.round(top + banner * (band + 1) / bands);
                float fade = Math.max(0, (band + 0.5f) / bands - 0.25f) / 0.75f;
                r.rect(x, y0, w, y1 - y0, (Math.round(fade * fade * 255) << 24) | (PANEL & 0xffffff));
            }
        }
        r.textMiddle(r.fit(active.name(), w - 70 * s, 4 * s), x + 35 * s, top + banner - 58 * s, 48 * s, 4 * s, TEXT);
        float line = top + banner + 8 * s;
        r.textMiddle(r.fit(config.describe(active) + (active.imported() ? "  |  Imported" : "  |  Managed"), w - 70 * s,
                2.5f * s), x + 35 * s, line, 32 * s, 2.5f * s, TEXT);
        r.textMiddle(r.fit(active.gameDir().toString(), w - 70 * s, 2.1f * s), x + 35 * s, line + 34 * s, 30 * s, 2.1f * s, DIM);
        r.textMiddle(config.modCount() + " mods  |  Heap " + config.heapMib + " MiB  |  GPU " + config.arenaMib + " MiB",
                x + 35 * s, line + 66 * s, 30 * s, 2.3f * s, DIM);
        if (crash != null) r.textMiddle(r.fit("Last session: " + crash.title(), w - 70 * s, 2.1f * s),
                x + 35 * s, line + 98 * s, 30 * s, 2.1f * s, WARN);

        float py = top + cardH + 22 * s, ph = 86 * s;
        boolean playOn = focus == Focus.DETAIL && detailSelected == 0 && !editor.active();
        playButton(x, py, w, ph, playOn);
        hit(x, py, w, ph, () -> { focus = Focus.DETAIL; detailSelected = 0; }, Input.Action.CONFIRM);
        for (int i = 1; i < HOME_ACTIONS.size(); i++) {
            float y = py + ph + 16 * s + (i - 1) * 42 * s;
            boolean highlighted = focus == Focus.DETAIL && detailSelected == i && !editor.active();
            row(x, y, w, 38 * s, highlighted);
            r.textMiddle(HOME_ACTIONS.get(i), x + 34 * s, y, 38 * s, 2.6f * s, highlighted ? TEXT : DIM);
            int index = i;
            hit(x, y, w, 38 * s, () -> { focus = Focus.DETAIL; detailSelected = index; }, Input.Action.CONFIRM);
        }
    }

    /** A page's title, with a smaller note on the same baseline after it. */
    private void pageTitle(String title, String note) {
        float s = r.s, baseline = r.middleY(160 * s, 52 * s, 3.6f * s), x = 120 * s;
        r.text(title, x, baseline, 3.6f * s, TEXT);
        float end = r.width(title, 3.6f * s) + 30 * s;
        if (!note.isEmpty()) r.textBaseline(r.fit(note, 1500 * s, 2.3f * s), x + end, r.middleY(160 * s, 52 * s, 3.6f * s)
                + r.capBottom(3.6f * s), 2.3f * s, DIM);
    }

    /** A list row: a panel, and when chosen a lighter one marked with the accent on its left. */
    private void row(float x, float y, float w, float h, boolean chosen) {
        float s = r.s;
        if (chosen) {
            r.roundRect(x, y, w, h, 12 * s, PANEL_HI);
            r.roundRect(x, y + h * 0.18f, 6 * s, h * 0.64f, 3 * s, ACCENT);
        }
    }

    /** Amethyst's play button: a raised amethyst block with the label in the middle. */
    private void playButton(float x, float y, float w, float h, boolean focused) {
        float s = r.s;
        if (focused) r.roundRect(x - 5 * s, y - 5 * s, w + 10 * s, h + 10 * s, 18 * s, WHITE);
        r.roundRect(x, y, w, h, 14 * s, ACCENT_DARK);
        r.roundRect(x, y, w, h - 7 * s, 14 * s, focused ? ACCENT_HI : ACCENT);
        r.rect(x + 14 * s, y + 4 * s, w - 28 * s, 3 * s, 0x40ffffff);
        String label = "PLAY";
        // Centred on the raised face, above the darker lip.
        float scale = 4.4f * s, tw = r.width(label, scale), face = h - 7 * s, ty = r.middleY(y, face, scale);
        r.text(label, x + (w - tw) / 2 + 3 * s, ty + 3 * s, scale, 0x60000000);
        r.text(label, x + (w - tw) / 2, ty, scale, TEXT);
        if (focused) r.button("cross", x + (w - tw) / 2 - 64 * s, y + (face - 38 * s) / 2, 38 * s);
    }

    /** Per runtime profile: the version line and the loader's initial; reading it each frame would read files. */
    private final java.util.Map<Path, String[]> instanceInfo = new java.util.HashMap<>();

    private String[] instanceInfo(InstanceStore.Instance instance) {
        return instanceInfo.computeIfAbsent(instance.runtimeProfile(), profile -> {
            VersionStore.Installed version = config.installed(instance);
            String loader = version != null ? version.loader().title
                    : config.loaderVersion.isEmpty() ? "Vanilla" : "Fabric";
            return new String[]{config.describe(instance), loader.substring(0, 1)};
        });
    }

    private static int loaderColor(String initial) {
        return switch (initial) {
            case "F" -> 0xffb88a52;
            case "Q" -> 0xff7a5ad1;
            default -> 0xff4f9a4a;
        };
    }

    private void drawVersions(float s) {
        r.text("New instance: choose Minecraft", 120 * s, 168 * s, 3.6f * s, TEXT);
        List<String> rows = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        rows.add("Loader:  < " + versionLoader.title + " >");
        colors.add(ACCENT_HI);
        java.util.Set<String> present = new java.util.HashSet<>();
        for (VersionStore.Installed installed : config.versions.list())
            if (installed.loader() == versionLoader) present.add(installed.minecraft());
        for (VersionStore.GameVersion version : catalog) {
            boolean ready = present.contains(version.id()) || builtIn(version, versionLoader);
            boolean legacy = VersionStore.compare(VersionStore.parts(version.id()), new int[]{1, 17}) < 0;
            rows.add("Minecraft " + version.id() + (ready ? "   (installed)" : "   (download)")
                    + (legacy ? "   older OpenGL" : ""));
            colors.add(ready ? TEXT : DIM);
        }
        list(rows, colors, 120 * s, 280 * s, 1680 * s, 12);
    }

    // ------------------------------------------------------------------ dialogs

    /** A dialog panel; everything drawn before it stops answering the mouse. */
    private void dialog(float x, float y, float w, float h) {
        clicks.modal();
        r.rect(0, 0, r.width, r.height, 0x80000000);
        r.roundRect(x, y, w, h, 20 * r.s, PANEL_HI);
        r.roundRect(x + 30 * r.s, y, w - 60 * r.s, 6 * r.s, 3 * r.s, ACCENT);
    }

    /** A two-way choice's buttons: "no" first (selected while yes is false), then "yes". */
    private void choice(float x, float y, String no, String yes, boolean yesChosen, int yesColor,
                        Runnable chooseNo, Runnable chooseYes) {
        float s = r.s;
        r.roundRect(x, y, 360 * s, 60 * s, 14 * s, yesChosen ? PANEL : ACCENT);
        r.textCentered(no, x, y, 360 * s, 60 * s, 3 * s, TEXT);
        hit(x, y, 360 * s, 60 * s, chooseNo, Input.Action.CONFIRM);
        r.roundRect(x + 410 * s, y, 360 * s, 60 * s, 14 * s, yesChosen ? yesColor : PANEL);
        r.textCentered(yes, x + 410 * s, y, 360 * s, 60 * s, 3 * s, yesChosen ? TEXT : DIM);
        hit(x + 410 * s, y, 360 * s, 60 * s, chooseYes, Input.Action.CONFIRM);
    }

    private void drawNameDialog(float s) {
        float x = 350 * s, y = 330 * s;
        dialog(x, y, 1220 * s, 320 * s);
        r.text(editingField == Field.JVM_ARGS ? "JVM ARGUMENTS" : editingField == Field.GAME_ARGS ? "MINECRAFT ARGUMENTS"
                : nameMode == NameMode.CREATE ? "CREATE INSTANCE" : nameMode == NameMode.CLONE
                ? "CLONE INSTANCE" : "RENAME INSTANCE", x + 40 * s, y + 35 * s, 3.5f * s, TEXT);
        String value = editor.value();
        String caret = value.substring(0, editor.caret()) + "|" + value.substring(editor.caret());
        r.roundRect(x + 30 * s, y + 100 * s, 1160 * s, 60 * s, 12 * s, PANEL);
        r.textMiddle(r.fit(caret, 1120 * s, 3.5f * s), x + 50 * s, y + 100 * s, 60 * s, 3.5f * s, ACCENT_HI);
        float by = y + 220 * s, bh = 60 * s;
        r.roundRect(x + 40 * s, by, 260 * s, bh, 14 * s, ACCENT);
        r.textCentered("Accept", x + 40 * s, by, 260 * s, bh, 3 * s, TEXT);
        hit(x + 40 * s, by, 260 * s, bh, null, Input.Action.CONFIRM);
        r.roundRect(x + 340 * s, by, 260 * s, bh, 14 * s, PANEL);
        r.textCentered("Cancel", x + 340 * s, by, 260 * s, bh, 3 * s, TEXT);
        hit(x + 340 * s, by, 260 * s, bh, null, Input.Action.BACK);
    }

    private void drawCloneOptions(float s) {
        float x = 410 * s, y = 350 * s;
        dialog(x, y, 1100 * s, 340 * s);
        r.text("CLONE " + pendingCloneName, x + 35 * s, y + 30 * s, 3.5f * s, TEXT);
        List<String> rows = List.of("Copy worlds: " + (copyWorlds ? "[x]" : "[ ]"), "Create clone", "Cancel");
        for (int i = 0; i < rows.size(); i++) {
            float rowY = y + (100 + i * 70) * s;
            row(x + 30 * s, rowY - 8 * s, 900 * s, 55 * s, i == cloneOptionSelected);
            r.text(rows.get(i), x + 55 * s, rowY, 3 * s, i == cloneOptionSelected ? TEXT : TEXT);
            int index = i;
            hit(x + 30 * s, rowY - 8 * s, 900 * s, 55 * s, () -> cloneOptionSelected = index, Input.Action.CONFIRM);
        }
    }

    private void drawInstanceRemoveDialog(float s) {
        InstanceStore.Instance value = config.instances.get(instanceRemovePending);
        if (value == null) {
            // Removed meanwhile (a failed modpack install cleans up after itself): nothing left to ask about.
            instanceRemovePending = null;
            return;
        }
        float x = 380 * s, y = 340 * s;
        dialog(x, y, 1160 * s, 350 * s);
        r.text(r.fit("Remove " + value.name() + "?", 1080 * s, 3.5f * s), x + 35 * s, y + 35 * s, 3.5f * s, TEXT);
        r.text(value.imported() ? "Only its registration will be removed. Game files stay in place."
                : "Its mods, configuration, and worlds will be deleted.", x + 35 * s, y + 115 * s, 2.5f * s, WARN);
        choice(x + 35 * s, y + 235 * s, "Cancel", "Remove", removeConfirmed, ERROR,
                () -> removeConfirmed = false, () -> removeConfirmed = true);
    }

    private void drawAccounts(float s) {
        r.text("Accounts", 120 * s, 168 * s, 3.6f * s, TEXT);
        String defaultId;
        try { defaultId = accounts().defaultAccountId(); }
        catch (IOException failure) {
            r.text(r.fit("Accounts unavailable: " + failure.getMessage(), 1680 * s, 3 * s), 120 * s, 280 * s, 3 * s, ERROR);
            return;
        }
        List<String> rows = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (AccountStore.Account account : accountList()) {
            boolean isDefault = account.id().equals(defaultId);
            rows.add((isDefault ? "* " : "  ") + account.username()
                    + "   (Microsoft)"
                    + (isDefault ? "   default" : ""));
            colors.add(TEXT);
        }
        rows.add("Add Microsoft account");
        colors.add(ACCENT_HI);
        InstanceStore.Instance active = config.selectedInstance();
        rows.add("Account for " + active.name() + ":  < "
                + (active.accountId().isEmpty() ? "Follow default" : accountName(active.accountId())) + " >");
        colors.add(TEXT);
        list(rows, colors, 120 * s, 280 * s, 1680 * s, 12);
    }

    private void drawLoginDialog(float s) {
        MicrosoftAuthClient.DeviceSession session = login;
        float x = 260 * s, y = 230 * s, w = 1400 * s, h = 640 * s;
        dialog(x, y, w, h);
        r.text("SIGN IN WITH MICROSOFT", x + 40 * s, y + 35 * s, 3.5f * s, TEXT);
        if (loginQr != null) LoginQr.draw(r, loginQr, x + 40 * s, y + 100 * s, 480 * s);
        float tx = x + 580 * s;
        r.text("1. Scan the code or open:", tx, y + 110 * s, 2.6f * s, DIM);
        r.text(r.fit(session.verificationUri(), w - 620 * s, 3.2f * s), tx, y + 150 * s, 3.2f * s, ACCENT_HI);
        r.text("2. Enter this code:", tx, y + 230 * s, 2.6f * s, DIM);
        r.text(session.userCode(), tx, y + 275 * s, 6 * s, TEXT);
        long left = Math.max(0, Duration.between(Instant.now(), session.expiresAt()).getSeconds());
        r.text(String.format("Code expires in %d:%02d", left / 60, left % 60), tx, y + 400 * s, 2.6f * s,
                left < 60 ? WARN : DIM);
        r.text("3. Sign in with the account that owns Minecraft.", tx, y + 450 * s, 2.6f * s, DIM);
        r.roundRect(tx, y + 545 * s, 260 * s, 60 * s, 12 * s, PANEL);
        r.text("Cancel", tx + 30 * s, y + 557 * s, 3 * s, TEXT);
        hit(tx, y + 545 * s, 260 * s, 60 * s, null, Input.Action.BACK);
    }

    private void drawAccountRemoveDialog(float s) {
        float x = 380 * s, y = 340 * s;
        dialog(x, y, 1160 * s, 350 * s);
        r.text(r.fit("Sign out " + accountName(accountRemovePending) + "?", 1080 * s, 3.5f * s),
                x + 35 * s, y + 35 * s, 3.5f * s, TEXT);
        r.text("The saved sign-in is deleted. Instances set to it will ask for another.", x + 35 * s, y + 115 * s,
                2.5f * s, WARN);
        choice(x + 35 * s, y + 235 * s, "Cancel", "Sign out", removeConfirmed, ERROR,
                () -> removeConfirmed = false, () -> removeConfirmed = true);
    }

    private void drawRemoveDialog(float s) {
        float x = 400 * s, y = 350 * s;
        dialog(x, y, 1120 * s, 320 * s);
        String name = removePending.getFileName().toString();
        ModInspector.LocalMod info = modInfo.get(canonicalMod(removePending));
        r.text(r.fit("Remove " + (info != null ? info.name() : name) + "?", 1040 * s, 3.5f * s), x + 40 * s, y + 50 * s,
                3.5f * s, TEXT);
        r.text(r.fit("The mod file " + name + " will be deleted.", 1040 * s, 2.6f * s), x + 40 * s, y + 125 * s,
                2.6f * s, WARN);
        choice(x + 40 * s, y + 220 * s, "Cancel", "Remove", removeConfirmed, ERROR,
                () -> removeConfirmed = false, () -> removeConfirmed = true);
    }

    /** What installing a mod brings, what stops it, and Install / Cancel. */
    private void drawPlanDialog(float s) {
        ModInstaller.Plan p = plan;
        float x = 260 * s, w = 1400 * s, scale = 2.5f * s, lw = w - 80 * s;
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (ModInstaller.Entry e : p.entries()) {
            lines.add("+ " + e.title() + "  " + e.version().versionNumber() + "  (" + e.version().file().filename() + ")");
            colors.add(TEXT);
        }
        for (String b : p.blockers()) for (String l : r.wrap("x " + b, lw, scale)) { lines.add(l); colors.add(ERROR); }
        for (String wn : p.warnings()) for (String l : r.wrap("! " + wn, lw, scale)) { lines.add(l); colors.add(WARN); }
        int max = 13;
        float h = (135 + Math.min(max + 1, lines.size()) * 31 + 140) * s, y = (r.height - 170 * s - h) / 2 + 60 * s;
        dialog(x, y, w, h);
        r.text(r.fit("INSTALL " + planTitle.toUpperCase(java.util.Locale.ROOT), lw, 3.5f * s), x + 40 * s, y + 35 * s,
                3.5f * s, TEXT);
        r.text(r.fit("Into " + config.selectedInstance().name() + "  (" + config.describe(config.selectedInstance()) + ")",
                lw, 2.3f * s), x + 40 * s, y + 85 * s, 2.3f * s, DIM);
        float ly = y + 135 * s;
        for (int i = 0; i < Math.min(max, lines.size()); i++) {
            r.text(r.fit(lines.get(i), lw, scale), x + 40 * s, ly, scale, colors.get(i));
            ly += 31 * s;
        }
        if (lines.size() > max) r.text("... and " + (lines.size() - max) + " more lines", x + 40 * s, ly, scale, DIM);
        if (p.installable()) choice(x + 40 * s, y + h - 90 * s, "Install", "Cancel", dialogChoice == 1, PANEL_HI,
                () -> dialogChoice = 0, () -> dialogChoice = 1);
        else {
            r.rect(x + 40 * s, y + h - 90 * s, 360 * s, 60 * s, ACCENT);
            r.text("Close", x + 65 * s, y + h - 78 * s, 3 * s, TEXT);
            hit(x + 40 * s, y + h - 90 * s, 360 * s, 60 * s, () -> dialogChoice = 1, Input.Action.CONFIRM);
        }
    }

    /** What a modpack is, what installing it makes, and Create instance / Cancel. */
    private void drawPackDialog(float s) {
        PackInstaller.Pack p = pack;
        float x = 260 * s, w = 1400 * s, scale = 2.5f * s, lw = w - 80 * s;
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (!p.summary().isEmpty()) for (String l : r.wrap(p.summary(), lw, scale)) { lines.add(l); colors.add(DIM); }
        lines.add("Minecraft " + p.minecraft() + (p.loader() == null ? "" : "  with " + p.loader().title + " loader "
                + p.loaderVersion()));
        colors.add(TEXT);
        long mods = p.files().stream().filter(f -> f.path().startsWith("mods/")).count();
        lines.add(p.files().size() + " files to download (" + mods + " mods, "
                + String.format("%.1f MiB", p.downloadBytes() / 1048576.0) + "), " + p.overrides() + " files from the pack itself");
        colors.add(TEXT);
        lines.add("Creates a new instance named \"" + p.name() + "\" and selects it.");
        colors.add(TEXT);
        lines.add("Mods with native libraries are installed but disabled; they cannot load on the console.");
        colors.add(DIM);
        for (String b : p.blockers()) for (String l : r.wrap("x " + b, lw, scale)) { lines.add(l); colors.add(ERROR); }
        for (String wn : p.warnings()) for (String l : r.wrap("! " + wn, lw, scale)) { lines.add(l); colors.add(WARN); }
        int max = 14;
        float h = (100 + Math.min(max + 1, lines.size()) * 31 + 140) * s, y = (r.height - 170 * s - h) / 2 + 60 * s;
        dialog(x, y, w, h);
        r.text(r.fit("MODPACK  " + p.name() + (p.versionId().isEmpty() ? "" : "  " + p.versionId()), lw, 3.5f * s),
                x + 40 * s, y + 35 * s, 3.5f * s, TEXT);
        float ly = y + 100 * s;
        for (int i = 0; i < Math.min(max, lines.size()); i++) {
            r.text(r.fit(lines.get(i), lw, scale), x + 40 * s, ly, scale, colors.get(i));
            ly += 31 * s;
        }
        if (lines.size() > max) r.text("... and " + (lines.size() - max) + " more lines", x + 40 * s, ly, scale, DIM);
        if (p.installable()) choice(x + 40 * s, y + h - 90 * s, "Create instance", "Cancel", dialogChoice == 1, PANEL_HI,
                () -> dialogChoice = 0, () -> dialogChoice = 1);
        else {
            r.rect(x + 40 * s, y + h - 90 * s, 360 * s, 60 * s, ACCENT);
            r.text("Close", x + 65 * s, y + h - 78 * s, 3 * s, TEXT);
            hit(x + 40 * s, y + h - 90 * s, 360 * s, 60 * s, () -> dialogChoice = 1, Input.Action.CONFIRM);
        }
    }

    private void drawSettings(float s) {
        InstanceStore.Instance current = config.selectedInstance();
        pageTitle("Instance settings", current.name());
        String[][] rows = {
                {"Java heap", "< " + config.heapMib + " MiB >"},
                {"GPU memory", "< " + config.arenaMib + " MiB >"},
                {"JVM arguments", current.jvmArgs().isEmpty() ? "None" : current.jvmArgs()},
                {"Minecraft arguments", current.gameArgs().isEmpty() ? "None" : current.gameArgs()},
                {"Runner debug log (all instances)", "< " + (config.debugLog ? "On" : "Off") + " >"},
                {"Save", ""}, {"Back", ""}};
        float x = 120 * s, w = 1000 * s, rowH = 72 * s, y = 250 * s;
        for (int i = 0; i < rows.length; i++) {
            boolean on = i == selected && !editor.active() && !systemKeyboardOpen;
            float ry = y + i * rowH + (i >= 5 ? 24 * s : 0);
            if (i < 5) r.roundRect(x, ry, w, rowH - 10 * s, 12 * s, PANEL);
            row(x, ry, w, rowH - 10 * s, on);
            float rh = rowH - 10 * s;
            r.textMiddle(rows[i][0], x + 34 * s, ry, rh, 2.7f * s, on ? TEXT : i >= 5 ? DIM : TEXT);
            if (!rows[i][1].isEmpty()) {
                String value = r.fit(rows[i][1], 520 * s, 2.5f * s);
                boolean none = rows[i][1].equals("None");
                r.textMiddle(value, x + w - 34 * s - r.width(value, 2.5f * s), ry, rh, 2.5f * s, none ? DIM : ACCENT_HI);
            }
            int index = i;
            hit(x, ry, w, rowH - 10 * s, () -> selected = index, Input.Action.CONFIRM);
        }
        // What the highlighted setting does, as Amethyst's profile editor explains its fields.
        String[] help = {
                "Memory for Minecraft itself. More helps big modpacks; together with GPU memory it is limited to "
                        + LauncherConfig.MAX_TOTAL_MIB + " MiB.",
                "Memory reserved for the graphics driver: textures, shaders and chunk meshes.",
                "Extra options for the Java virtual machine, such as -XX:+UseG1GC or -Dfabric.debug=true. "
                        + "Memory, class paths and the runner's own properties are set by the launcher.",
                "Extra Minecraft arguments, such as --quickPlaySingleplayer \"My World\" or --demo. "
                        + "The account, version and game folder are set by the launcher.",
                "Writes the runner's detailed log for every instance. Useful when reporting a problem.",
                "Keeps the memory and log settings. Arguments are kept as soon as you enter them.",
                "Returns to the instance."};
        float px = 1160 * s, pw = 640 * s;
        r.roundRect(px, y, pw, 5 * rowH - 10 * s, 18 * s, PANEL);
        String title = rows[Math.min(selected, rows.length - 1)][0];
        r.text(r.fit(title, pw - 70 * s, 3 * s), px + 35 * s, y + 30 * s, 3 * s, TEXT);
        float ty = y + 85 * s;
        for (String line : r.wrap(help[Math.min(selected, help.length - 1)], pw - 70 * s, 2.3f * s)) {
            r.text(line, px + 35 * s, ty, 2.3f * s, DIM);
            ty += 32 * s;
        }
    }

    /** New instance: a version of Minecraft to set up, or a modpack that brings its own. */
    private void drawCreate(float s) {
        r.text("New instance", 120 * s, 168 * s, 3.6f * s, TEXT);
        String[][] cards = {
                {"Minecraft", "Choose a Minecraft version with Fabric, Quilt or no loader. Add mods afterwards.", "V"},
                {"Modpack", "Browse Modrinth modpacks. A modpack brings its version, loader and mods.", "M"}};
        float w = 820 * s, h = 520 * s, gap = 40 * s, x0 = (r.width - 2 * w - gap) / 2, y = 260 * s;
        for (int i = 0; i < 2; i++) {
            float x = x0 + i * (w + gap);
            boolean on = selected == i;
            if (on) r.roundRect(x - 5 * s, y - 5 * s, w + 10 * s, h + 10 * s, 26 * s, ACCENT);
            r.roundRect(x, y, w, h, 22 * s, on ? PANEL_HI : PANEL);
            if (i == 0 && r.artwork() > 0)
                r.roundImage(r.artwork(), x + 40 * s, y + 40 * s, 160 * s, 160 * s, 24 * s, on ? PANEL_HI : PANEL);
            else {
                r.roundRect(x + 40 * s, y + 40 * s, 160 * s, 160 * s, 24 * s, ACCENT_DARK);
                r.textCentered(cards[i][2], x + 40 * s, y + 40 * s, 160 * s, 160 * s, 8 * s, TEXT);
            }
            r.text(cards[i][0], x + 40 * s, y + 240 * s, 4.2f * s, TEXT);
            float ty = y + 310 * s;
            for (String line : r.wrap(cards[i][1], w - 80 * s, 2.5f * s)) {
                r.text(line, x + 40 * s, ty, 2.5f * s, DIM);
                ty += 34 * s;
            }
            int index = i;
            hit(x, y, w, h, () -> selected = index, Input.Action.CONFIRM);
        }
    }

    // ------------------------------------------------------------------ mods page

    private void drawMods(float s) {
        long on = mods.stream().filter(LauncherScreen::enabled).count();
        pageTitle("Mods", on + " enabled, " + (mods.size() - on) + " disabled  |  " + config.selectedInstance().name());
        int count = 1 + mods.size();
        if (selected >= count) selected = count - 1;
        float x = 120 * s, w = 880 * s, rowH = 66 * s;
        scrollList(scrollers[Page.MODS.ordinal()], count, selected, x, 270 * s, w, 9 * rowH, rowH, (i, y) -> {
            boolean on2 = i == selected && !editor.active() && !systemKeyboardOpen;
            r.roundRect(x, y, w, rowH - 6 * s, 12 * s, on2 ? PANEL_HI : PANEL);
            if (on2) r.roundRect(x, y + 8 * s, 6 * s, rowH - 6 * s - 16 * s, 3 * s, ACCENT);
            hit(x, y, w, rowH - 6 * s, () -> selected = i, Input.Action.CONFIRM);
            if (i == 0) {
                r.textMiddle("+ Browse Modrinth for mods", x + 30 * s, y, rowH - 6 * s, 3 * s, ACCENT_HI);
                return;
            }
            Path mod = mods.get(i - 1);
            ModInspector.LocalMod info = modInfo.get(canonicalMod(mod));
            boolean enabledMod = enabled(mod);
            drawModIcon(mod, info, x + 14 * s, y + 7 * s, 46 * s, enabledMod);
            String n = mod.getFileName().toString();
            String name = info != null ? info.name() : enabledMod ? n.substring(0, n.length() - 4)
                    : n.substring(0, n.length() - 13);
            String version = info != null ? info.version() : "";
            // One line when the version is unknown, else name over version, centred on the row.
            if (version.isEmpty())
                r.textMiddle(r.fit(name, w - 330 * s, 2.8f * s), x + 76 * s, y, rowH - 6 * s, 2.8f * s, enabledMod ? TEXT : DIM);
            else {
                r.textMiddle(r.fit(name, w - 330 * s, 2.8f * s), x + 76 * s, y + 4 * s, 30 * s, 2.8f * s, enabledMod ? TEXT : DIM);
                r.textMiddle(r.fit(version, w - 330 * s, 2.1f * s), x + 76 * s, y + 33 * s, 24 * s, 2.1f * s, DIM);
            }
            String state = enabledMod ? "enabled" : "disabled";
            r.textMiddle(state, x + w - r.width(state, 2.3f * s) - 24 * s, y, rowH - 6 * s, 2.3f * s, enabledMod ? GOOD : WARN);
        });

        // The selected mod's details, with Enable/Disable and Remove.
        float px = 1040 * s, py = 270 * s, pw = 760 * s, ph = 585 * s;
        r.roundRect(px, py, pw, ph, 12 * s, PANEL);
        if (selected == 0 || selected > mods.size()) {
            r.text("MODRINTH", px + 35 * s, py + 30 * s, 3.5f * s, TEXT);
            float ty = py + 90 * s;
            for (String line : r.wrap("Search Modrinth for mods that run on " + target(ModrinthClient.Type.MOD)
                    + ", read their descriptions, look through their screenshots and pick a version. Every install is "
                    + "checked for the console first. Modpacks create a new instance of their own.", pw - 70 * s, 2.5f * s)) {
                r.text(line, px + 35 * s, ty, 2.5f * s, DIM);
                ty += 32 * s;
            }
            return;
        }
        Path mod = mods.get(selected - 1);
        ModInspector.LocalMod info = modInfo.get(canonicalMod(mod));
        boolean enabledMod = enabled(mod);
        drawModIcon(mod, info, px + 35 * s, py + 35 * s, 110 * s, true);
        String n = mod.getFileName().toString();
        r.text(r.fit(info != null ? info.name() : n, pw - 200 * s, 3.5f * s), px + 170 * s, py + 40 * s, 3.5f * s, TEXT);
        if (info != null) {
            r.text(r.fit(info.version(), pw - 200 * s, 2.4f * s), px + 170 * s, py + 90 * s, 2.4f * s, DIM);
            if (!info.authors().isEmpty())
                r.text(r.fit("by " + String.join(", ", info.authors()), pw - 200 * s, 2.3f * s), px + 170 * s,
                        py + 122 * s, 2.3f * s, DIM);
        }
        float ty = py + 175 * s;
        long size;
        try { size = Files.size(mod); } catch (IOException gone) { size = 0; }
        r.text(r.fit(n + "   " + String.format("%.1f KiB", size / 1024.0), pw - 70 * s, 2.2f * s), px + 35 * s, ty,
                2.2f * s, DIM);
        ty += 45 * s;
        String text = info == null ? "Reading the mod..." : info.description().isBlank() ? "No description." : info.description();
        int lines = 0;
        for (String line : r.wrap(text.replace('\n', ' '), pw - 70 * s, 2.5f * s)) {
            if (++lines > 9) break;
            r.text(line, px + 35 * s, ty, 2.5f * s, TEXT);
            ty += 32 * s;
        }
        float by = py + ph - 85 * s;
        r.roundRect(px + 35 * s, by, 300 * s, 58 * s, 14 * s, enabledMod ? PANEL_HI : ACCENT);
        r.textCentered(enabledMod ? "Disable" : "Enable", px + 35 * s, by, 300 * s, 58 * s, 3 * s, TEXT);
        hit(px + 35 * s, by, 300 * s, 58 * s, null, Input.Action.CONFIRM);
        r.roundRect(px + 365 * s, by, 300 * s, 58 * s, 14 * s, ERROR);
        r.textCentered("Remove", px + 365 * s, by, 300 * s, 58 * s, 3 * s, TEXT);
        hit(px + 365 * s, by, 300 * s, 58 * s, null, Input.Action.ALT2);
    }

    /** A mod's own icon from its JAR, else a tile with its first letter. */
    private void drawModIcon(Path mod, ModInspector.LocalMod info, float x, float y, float size, boolean bright) {
        ImageCache.Picture icon = info == null || info.icon() == null ? null
                : images.get(canonicalMod(mod).toString(), info.icon(), ImageCache.Size.ICON);
        if (icon != null) {
            r.image(icon.texture(), x, y, size, size, bright ? WHITE : 0x80ffffff);
            return;
        }
        r.roundRect(x, y, size, size, size * 0.2f, CHIP);
        String n = info != null && !info.name().isEmpty() ? info.name() : mod.getFileName().toString();
        String letter = n.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        r.textCentered(letter, x, y, size, size, size / 16f, DIM);
    }

    // ------------------------------------------------------------------ browse page

    private void drawBrowse(float s) {
        boolean editing = editor.active() && editingField == Field.SEARCH;
        // Row 0: the Mods / Modpacks tabs.
        float tx = 120 * s, ty = 210 * s;
        for (ModrinthClient.Type type : ModrinthClient.Type.values()) {
            boolean current = type == browseType;
            r.roundRect(tx, ty, 230 * s, 56 * s, 14 * s, current ? ACCENT : PANEL);
            r.textCentered(type.title, tx, ty, 230 * s, 56 * s, 3 * s, TEXT);
            ModrinthClient.Type target = type;
            clicks.add(tx, ty, 230 * s, 56 * s, () -> selected = 0, () -> {
                selected = 0;
                if (!idle() || browseType == target) return;
                if (target == ModrinthClient.Type.MOD && config.loader() == VersionStore.Loader.VANILLA) {
                    setStatus("Vanilla instances do not load mods. Create a Fabric or Quilt instance, or get a modpack.", WARN);
                    return;
                }
                browseType = target;
                search(false);
            });
            tx += 240 * s;
        }
        if (selected == 0 && !editing) r.roundFrame(120 * s - 6 * s, ty - 6 * s, 482 * s, 68 * s, 18 * s, WHITE);
        // Row 1: the search box.
        float sx = 640 * s, sw = 740 * s;
        r.roundRect(sx, ty, sw, 56 * s, 12 * s, PANEL_HI);
        if (selected == 1 || editing) r.roundFrame(sx, ty, sw, 56 * s, 16 * s, editing ? ACCENT : WHITE);
        String shown;
        int shownColor = TEXT;
        if (editing) {
            String v = editor.value();
            shown = v.substring(0, editor.caret()) + "|" + v.substring(editor.caret());
        } else if (query.length() > 0) shown = query.toString();
        else { shown = "Search " + browseType.title.toLowerCase(java.util.Locale.ROOT) + "..."; shownColor = DIM; }
        r.textMiddle(r.fit(shown, sw - 40 * s, 3 * s), sx + 20 * s, ty, 56 * s, 3 * s, shownColor);
        hit(sx, ty, sw, 56 * s, () -> selected = 1, Input.Action.CONFIRM);
        // Row 2: the sort.
        float ox = 1400 * s, ow = 400 * s;
        r.roundRect(ox, ty, ow, 56 * s, 12 * s, PANEL);
        if (selected == 2 && !editing) r.roundFrame(ox, ty, ow, 56 * s, 16 * s, WHITE);
        r.textMiddle(r.fit("Sort: < " + sort.title + " >", ow - 30 * s, 2.6f * s), ox + 18 * s, ty, 56 * s, 2.6f * s, TEXT);
        hit(ox, ty, ow, 56 * s, () -> selected = 2, Input.Action.CONFIRM);

        r.text(r.fit((browseType == ModrinthClient.Type.MOD ? "Mods for " : "Modpacks for ") + target(browseType)
                + (searched && !busy ? String.format("   |   %,d found", totalHits) : ""), 1680 * s, 2.3f * s),
                120 * s, 282 * s, 2.3f * s, DIM);

        // The results, as cards, and "load more" after them, in a smoothly scrolled view.
        int cards = hits.size() + (hits.size() < totalHits ? 1 : 0);
        float cy = 318 * s, cardH = 106 * s, gap = 10 * s, cw = 1680 * s;
        if (hits.isEmpty() && !busy && searched)
            r.text("Nothing found. Try other words or another sort.", 140 * s, cy + 20 * s, 3 * s, DIM);
        scrollList(scrollers[Page.BROWSE.ordinal()], cards, selected >= 3 ? selected - 3 : -1, 120 * s, cy, cw,
                5 * (cardH + gap) - gap, cardH + gap, (i, y) -> {
            boolean on = selected == 3 + i && !editing;
            int index = 3 + i;
            if (i == hits.size()) {
                r.roundRect(120 * s, y, cw, 60 * s, 14 * s, on ? ACCENT : PANEL);
                String more = String.format("Load more  (%,d of %,d shown)", hits.size(), totalHits);
                r.textCentered(more, 120 * s, y, cw, 60 * s, 3 * s, TEXT);
                hit(120 * s, y, cw, 60 * s, () -> selected = index, Input.Action.CONFIRM);
                return;
            }
            drawCard(hits.get(i), 120 * s, y, cw, cardH, on);
            hit(120 * s, y, cw, cardH, () -> selected = index, Input.Action.CONFIRM);
        });
    }

    private void drawCard(ModrinthClient.Hit h, float x, float y, float w, float ch, boolean on) {
        float s = r.s;
        r.roundRect(x, y, w, ch, 12 * s, on ? PANEL_HI : PANEL);
        if (on) r.roundRect(x, y + 8 * s, 6 * s, ch - 16 * s, 3 * s, ACCENT);
        picture(h.iconUrl(), ImageCache.Size.ICON, x + 16 * s, y + 13 * s, 80 * s, 80 * s, false, h.title());
        float tx = x + 116 * s, right = x + w - 24 * s;
        String stats = compact(h.downloads()) + " downloads   " + compact(h.follows()) + " followers";
        float statsW = r.width(stats, 2.2f * s);
        // Title, author and stats share one baseline; the description has its own line.
        float firstLine = y + 38 * s;
        r.textBaseline(stats, right - statsW, firstLine, 2.2f * s, DIM);
        String title = r.fit(h.title(), right - statsW - tx - 260 * s, 3.2f * s);
        r.textBaseline(title, tx, firstLine, 3.2f * s, TEXT);
        float aw = tx + r.width(title, 3.2f * s) + 16 * s;
        r.textBaseline(r.fit("by " + h.author(), right - statsW - aw - 20 * s, 2.2f * s), aw, firstLine, 2.2f * s, DIM);
        r.textBaseline(r.fit(h.description(), right - tx, 2.4f * s), tx, y + 68 * s, 2.4f * s, TEXT);
        float cx = tx;
        for (String c : h.categories()) {
            if (LOADERS.contains(c)) continue;
            String label = c.replace('-', ' ');
            float cw = r.width(label, 2 * s) + 20 * s;
            if (cx + cw > right - 260 * s) break;
            r.roundRect(cx, y + 76 * s, cw, 24 * s, 12 * s, CHIP);
            r.textCentered(label, cx, y + 76 * s, cw, 24 * s, 2 * s, DIM);
            cx += cw + 8 * s;
        }
        if (!h.updated().isEmpty()) {
            String updated = "Updated " + date(h.updated());
            r.textMiddle(updated, right - r.width(updated, 2 * s), y + 76 * s, 24 * s, 2 * s, DIM);
        }
    }

    /**
     * A picture fitted into the box (contain) or filling it (cover, cropped); a tile while it loads, or
     * the name's first letter when there is none.
     */
    private boolean picture(String url, ImageCache.Size size, float x, float y, float w, float h, boolean cover, String name) {
        ImageCache.Picture p = images.get(url, size);
        if (p == null) {
            r.rect(x, y, w, h, CHIP);
            if (name != null && !name.isEmpty() && !images.loading(url, size)) {
                String letter = name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
                r.textCentered(letter, x, y, w, h, Math.min(w, h) / 16f, DIM);
            }
            return false;
        }
        float ratio = (float) p.width() / p.height(), box = w / h;
        if (cover) {
            float u0 = 0, v0 = 0, u1 = 1, v1 = 1;
            if (ratio > box) { float keep = box / ratio; u0 = (1 - keep) / 2; u1 = u0 + keep; }
            else { float keep = ratio / box; v0 = (1 - keep) / 2; v1 = v0 + keep; }
            r.image(p.texture(), x, y, w, h, u0, v0, u1, v1, WHITE);
        } else {
            float dw = w, dh = h;
            if (ratio > box) dh = w / ratio; else dw = h * ratio;
            r.image(p.texture(), x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, WHITE);
        }
        return true;
    }

    /** Loader names Modrinth lists among a project's categories; the cards show only real categories. */
    private static final java.util.Set<String> LOADERS = java.util.Set.of("fabric", "quilt", "forge", "neoforge",
            "liteloader", "rift", "modloader", "minecraft", "datapack", "iris", "optifine", "canvas", "vanilla");

    private static String compact(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1e6);
        if (n >= 1_000) return String.format("%.1fk", n / 1e3);
        return Long.toString(n);
    }

    private static String date(String iso) {
        return iso.length() >= 10 ? iso.substring(0, 10) : iso;
    }

    // ------------------------------------------------------------------ project page

    private static final int GALLERY_COLUMNS = 4;
    /** Wrapped description text, per block, for the width it was wrapped at. */
    private final Map<Markdown.Block, List<String>> wrapped = new java.util.IdentityHashMap<>();
    private float wrappedWidth;
    private List<Markdown.Block> wrappedFor;

    private void drawProject(float s) {
        ModrinthClient.Hit h = chosen;
        if (h == null) return;
        ModrinthClient.Project p = project;
        ModrinthClient.GalleryImage banner = p == null ? null : p.banner();
        float x = 120 * s, w = 1680 * s, top = 205 * s;
        float headerY;
        if (banner != null) {
            float bh = 170 * s;
            picture(banner.rawUrl(), ImageCache.Size.BANNER, x, top, w, bh, true, null);
            r.rect(x, top + bh - 60 * s, w, 60 * s, 0x90101216);
            headerY = top + bh + 12 * s;
            picture(p.iconUrl(), ImageCache.Size.ICON, x + 24 * s, top + bh - 70 * s, 110 * s, 110 * s, false, h.title());
        } else {
            headerY = top;
            picture(h.iconUrl(), ImageCache.Size.ICON, x + 24 * s, top, 110 * s, 110 * s, false, h.title());
        }
        float textX = x + 160 * s;
        float titleY = banner != null ? headerY - 4 * s : headerY + 6 * s;
        r.text(r.fit(h.title(), x + w - 590 * s - textX, 4 * s), textX, titleY, 4 * s, TEXT);
        String meta = "by " + h.author() + "   |   " + compact(p != null ? p.downloads() : h.downloads()) + " downloads   |   "
                + compact(p != null ? p.followers() : h.follows()) + " followers"
                + (p != null && !p.license().isEmpty() ? "   |   " + p.license() : "")
                + (p != null && !p.updated().isEmpty() ? "   |   updated " + date(p.updated()) : "");
        // Install and Back, on the right of the header; the text to their left stops short of them.
        boolean pack2 = projectType() == ModrinthClient.Type.MODPACK;
        String installLabel = pack2 ? "Create instance" : "Install";
        float bx = x + w - 560 * s, by = banner != null ? headerY - 6 * s : headerY + 4 * s;
        float textW = bx - textX - 30 * s;
        r.text(r.fit(meta, textW, 2.2f * s), textX, titleY + 46 * s, 2.2f * s, DIM);
        if (banner == null) r.text(r.fit(h.description(), textW, 2.4f * s), textX, titleY + 78 * s, 2.4f * s, TEXT);

        boolean actions = zone == Zone.ACTIONS && galleryOpen < 0;
        r.roundRect(bx, by, 340 * s, 58 * s, 14 * s, actions && actionSelected == 0 ? ACCENT : PANEL_HI);
        r.textCentered(installLabel, bx, by, 340 * s, 58 * s, 3 * s, actions && actionSelected == 0 ? TEXT : ACCENT_HI);
        hit(bx, by, 340 * s, 58 * s, () -> { zone = Zone.ACTIONS; actionSelected = 0; }, Input.Action.CONFIRM);
        r.roundRect(bx + 360 * s, by, 200 * s, 58 * s, 14 * s, actions && actionSelected == 1 ? ACCENT : PANEL_HI);
        r.textCentered("Back", bx + 360 * s, by, 200 * s, 58 * s, 3 * s, TEXT);
        hit(bx + 360 * s, by, 200 * s, 58 * s, () -> { zone = Zone.ACTIONS; actionSelected = 1; }, Input.Action.CONFIRM);

        // Tabs.
        float tabsY = (banner != null ? headerY + 70 * s : headerY + 125 * s);
        String[] titles = {"Description", "Gallery" + (p != null ? " (" + p.gallery().size() + ")" : ""), "Versions"};
        float tx = x;
        for (Tab t : Tab.values()) {
            String label = titles[t.ordinal()];
            float tw = r.width(label, 3 * s) + 50 * s;
            boolean current = t == tab;
            r.roundRect(tx, tabsY, tw, 52 * s, 12 * s, current ? PANEL_HI : PANEL);
            if (current) r.rect(tx, tabsY + 48 * s, tw, 4 * s, ACCENT);
            r.textMiddle(label, tx + 25 * s, tabsY, 52 * s, 3 * s, current ? TEXT : DIM);
            if (zone == Zone.TABS && current && galleryOpen < 0) r.roundFrame(tx, tabsY, tw, 52 * s, 16 * s, WHITE);
            Tab target = t;
            clicks.add(tx, tabsY, tw, 52 * s, null, () -> {
                if (busy && !cancellable) return;
                zone = Zone.TABS;
                selectTab(target);
            });
            tx += tw + 10 * s;
        }

        float cy = tabsY + 70 * s, cb = r.height - 175 * s, ch = cb - cy;
        r.roundRect(x, cy, w, ch, 12 * s, PANEL);
        if (zone == Zone.CONTENT && galleryOpen < 0) r.rect(x, cy, 4 * s, ch, ACCENT);
        if (p == null) {
            r.text(busy ? "Loading..." : "Could not load the project.", x + 30 * s, cy + 30 * s, 3 * s, DIM);
            return;
        }
        switch (tab) {
            case DESCRIPTION -> drawDescription(x, cy, w, ch);
            case GALLERY -> drawGallery(p, x, cy, w, ch);
            case VERSIONS -> drawVersionList(x, cy, w, ch);
        }
        if (galleryOpen >= 0 && galleryOpen < p.gallery().size()) drawViewer(p);
    }

    private void drawDescription(float x, float y, float w, float h) {
        float s = r.s, pad = 30 * s, width = w - 2 * pad;
        if (wrappedFor != description || wrappedWidth != width) {
            wrapped.clear();
            wrappedFor = description;
            wrappedWidth = width;
        }
        descriptionView = h;
        // The text's height is known once it is laid out; last frame's is used for the limit.
        float offset = descriptionScroll.update(descriptionHeight, h, frameSeconds);
        clicks.wheel(x, y, w, h, amount -> descriptionScroll.wheel(amount, 120 * s));
        clicks.add(x, y, w, h, null, () -> { if (galleryOpen < 0) zone = Zone.CONTENT; });
        r.clip(x, y, w, h);
        float cy = y + pad - offset;
        if (description.isEmpty()) r.text("No description.", x + pad, cy, 3 * s, DIM);
        for (Markdown.Block b : description) {
            switch (b.kind()) {
                case HEADING -> {
                    float scale = (b.level() <= 1 ? 4f : b.level() == 2 ? 3.4f : 3f) * s;
                    cy += 14 * s;
                    for (String line : lines(b, width, scale)) {
                        if (visible(cy, 10 * scale, y, h)) r.text(line, x + pad, cy, scale, TEXT);
                        cy += 10 * scale;
                    }
                    cy += 8 * s;
                }
                case TEXT, QUOTE -> {
                    float scale = 2.5f * s, indent = b.kind() == Markdown.Kind.QUOTE ? 30 * s : 0;
                    float start = cy;
                    for (String line : lines(b, width - indent, scale)) {
                        if (visible(cy, 32 * s, y, h)) r.text(line, x + pad + indent, cy, scale, b.kind() == Markdown.Kind.QUOTE ? DIM : TEXT);
                        cy += 32 * s;
                    }
                    if (indent > 0) r.rect(x + pad, start - 4 * s, 5 * s, cy - start, CHIP);
                    cy += 14 * s;
                }
                case BULLET -> {
                    float scale = 2.5f * s, indent = (Math.abs(b.level() + (b.level() < 0 ? 1 : 0)) + 1) * 30 * s;
                    boolean first = true;
                    for (String line : lines(b, width - indent, scale)) {
                        if (visible(cy, 32 * s, y, h)) {
                            if (first && b.level() >= 0) r.text("*", x + pad + indent - 22 * s, cy, scale, ACCENT_HI);
                            r.text(line, x + pad + indent, cy, scale, TEXT);
                        }
                        first = false;
                        cy += 32 * s;
                    }
                    cy += 4 * s;
                }
                case CODE -> {
                    float scale = 2.2f * s;
                    if (visible(cy, 28 * s, y, h)) {
                        r.rect(x + pad, cy - 3 * s, width, 28 * s, DARK);
                        r.text(r.fit(b.text(), width - 20 * s, scale), x + pad + 10 * s, cy, scale, DIM);
                    }
                    cy += 28 * s;
                }
                case RULE -> {
                    cy += 12 * s;
                    if (visible(cy, 4 * s, y, h)) r.rect(x + pad, cy, width, 2 * s, CHIP);
                    cy += 18 * s;
                }
                case IMAGE -> {
                    // Pictures load only when near the view; until then they hold a placeholder's height.
                    boolean near = cy < y + h * 2 && cy > y - h * 2;
                    ImageCache.Picture pic = near ? images.get(b.text(), ImageCache.Size.BANNER) : null;
                    float iw, ih;
                    if (pic != null) {
                        iw = Math.min(width, pic.width() * s * 1.2f);
                        ih = iw * pic.height() / pic.width();
                        if (visible(cy, ih, y, h)) r.image(pic.texture(), x + pad, cy, iw, ih, WHITE);
                    } else {
                        boolean failed = near && !images.loading(b.text(), ImageCache.Size.BANNER);
                        iw = width;
                        ih = failed ? 0 : 160 * s;
                        if (!failed && visible(cy, ih, y, h)) r.rect(x + pad, cy, Math.min(width, 480 * s), ih, CHIP);
                    }
                    cy += ih + (ih > 0 ? 14 * s : 0);
                }
            }
        }
        r.unclip();
        descriptionHeight = cy + offset - y + pad;
        scrollbar(descriptionScroll, descriptionHeight, x + w - 12 * s, y, h);
    }

    private List<String> lines(Markdown.Block b, float width, float scale) {
        return wrapped.computeIfAbsent(b, k -> r.wrap(k.text(), width, scale));
    }

    private static boolean visible(float top, float height, float viewTop, float viewHeight) {
        return top + height >= viewTop && top <= viewTop + viewHeight;
    }

    private void drawGallery(ModrinthClient.Project p, float x, float y, float w, float h) {
        float s = r.s, pad = 24 * s;
        List<ModrinthClient.GalleryImage> gallery = p.gallery();
        if (gallery.isEmpty()) {
            r.text("This project has no gallery.", x + 30 * s, y + 30 * s, 3 * s, DIM);
            return;
        }
        float tileW = (w - pad * (GALLERY_COLUMNS + 1)) / GALLERY_COLUMNS, tileH = tileW * 9 / 16, rowH = tileH + 46 * s + pad;
        int rows = (gallery.size() + GALLERY_COLUMNS - 1) / GALLERY_COLUMNS;
        scrollList(galleryScroll, rows, zone == Zone.CONTENT ? gallerySelected / GALLERY_COLUMNS : -1,
                x, y + pad, w - 14 * s, h - pad, rowH, (row, ty) -> {
            for (int col = 0; col < GALLERY_COLUMNS; col++) {
                int i = row * GALLERY_COLUMNS + col;
                if (i >= gallery.size()) break;
                float tx = x + pad + col * (tileW + pad);
                boolean on = i == gallerySelected && zone == Zone.CONTENT;
                picture(gallery.get(i).url(), ImageCache.Size.THUMB, tx, ty, tileW, tileH, true, null);
                if (on) r.frame(tx - 4 * s, ty - 4 * s, tileW + 8 * s, tileH + 8 * s, 4 * s, ACCENT);
                r.text(r.fit(gallery.get(i).title().isEmpty() ? "Image " + (i + 1) : gallery.get(i).title(), tileW, 2.2f * s),
                        tx, ty + tileH + 10 * s, 2.2f * s, on ? TEXT : DIM);
                hit(tx, ty, tileW, tileH + 40 * s, () -> { zone = Zone.CONTENT; gallerySelected = i; }, Input.Action.CONFIRM);
            }
        });
    }

    /** One gallery image over the whole screen, with its title and description. */
    private void drawViewer(ModrinthClient.Project p) {
        float s = r.s;
        ModrinthClient.GalleryImage g = p.gallery().get(galleryOpen);
        clicks.modal();
        r.rect(0, 0, r.width, r.height, 0xf00b0c10);
        float bw = r.width - 240 * s, bh = r.height - 260 * s;
        if (!picture(g.rawUrl(), ImageCache.Size.FULL, 120 * s, 60 * s, bw, bh, false, null))
            picture(g.url(), ImageCache.Size.THUMB, 120 * s, 60 * s, bw, bh, false, null);
        String counter = (galleryOpen + 1) + " / " + p.gallery().size();
        r.text(counter, r.width - 120 * s - r.width(counter, 2.6f * s), r.height - 180 * s, 2.6f * s, DIM);
        r.text(r.fit(g.title(), bw - 300 * s, 3.2f * s), 120 * s, r.height - 185 * s, 3.2f * s, TEXT);
        r.text(r.fit(g.description(), bw, 2.4f * s), 120 * s, r.height - 140 * s, 2.4f * s, DIM);
        // Left and right thirds step through the images; the middle closes.
        clicks.add(0, 0, r.width / 3, r.height - 120 * s, null, () -> press(Input.Action.LEFT));
        clicks.add(r.width * 2 / 3, 0, r.width / 3, r.height - 120 * s, null, () -> press(Input.Action.RIGHT));
        clicks.add(r.width / 3, 0, r.width / 3, r.height - 120 * s, null, () -> press(Input.Action.CONFIRM));
        r.text("<", 40 * s, r.height / 2 - 30 * s, 7 * s, DIM);
        r.text(">", r.width - 80 * s, r.height / 2 - 30 * s, 7 * s, DIM);
    }

    private void drawVersionList(float x, float y, float w, float h) {
        float s = r.s, rowH = 54 * s;
        boolean content = zone == Zone.CONTENT && galleryOpen < 0;
        String filter = versionsAll ? "Showing all versions   < Only for this instance >"
                : "Showing versions for " + target(projectType()) + "   < All versions >";
        boolean filterOn = content && versionSelected == 0;
        r.rect(x + 20 * s, y + 16 * s, w - 40 * s, rowH - 8 * s, filterOn ? ACCENT : PANEL_HI);
        r.text(r.fit(filter, w - 80 * s, 2.6f * s), x + 40 * s, y + 27 * s, 2.6f * s, filterOn ? TEXT : TEXT);
        hit(x + 20 * s, y + 16 * s, w - 40 * s, rowH - 8 * s, () -> { zone = Zone.CONTENT; versionSelected = 0; },
                Input.Action.CONFIRM);
        List<ModrinthClient.VersionInfo> list = projectVersions;
        float ly = y + 16 * s + rowH;
        if (list == null) {
            r.text(busy ? "Loading versions..." : "Versions could not be loaded.", x + 40 * s, ly + 10 * s, 2.6f * s, DIM);
            return;
        }
        scrollList(versionScroll, list.size(), content && versionSelected > 0 ? versionSelected - 1 : -1,
                x, ly, w - 14 * s, y + h - ly - 10 * s, rowH, (i, ry) -> {
            ModrinthClient.VersionInfo v = list.get(i);
            boolean on = content && versionSelected == i + 1;
            r.roundRect(x + 20 * s, ry, w - 40 * s, rowH - 6 * s, 12 * s, on ? PANEL_HI : PANEL);
            if (on) r.roundRect(x + 20 * s, ry + 8 * s, 6 * s, rowH - 6 * s - 16 * s, 3 * s, ACCENT);
            int typeColor = "release".equals(v.type()) ? GOOD : "beta".equals(v.type()) ? WARN : ERROR;
            // Every column centred on the type chip's band.
            float band = ry + 11 * s, bandH = 28 * s;
            r.roundRect(x + 40 * s, band, 110 * s, bandH, bandH / 2, typeColor);
            r.textCentered(v.type(), x + 40 * s, band, 110 * s, bandH, 2.1f * s, DARK);
            r.textMiddle(r.fit(v.number(), 470 * s, 2.7f * s), x + 170 * s, band, bandH, 2.7f * s, TEXT);
            String games = String.join(", ", v.gameVersions().size() > 4
                    ? List.of(v.gameVersions().get(0), "...", v.gameVersions().get(v.gameVersions().size() - 1)) : v.gameVersions());
            r.textMiddle(r.fit(games, 400 * s, 2.3f * s), x + 670 * s, band, bandH, 2.3f * s, DIM);
            r.textMiddle(r.fit(String.join(", ", v.loaders()), 220 * s, 2.3f * s), x + 1090 * s, band, bandH, 2.3f * s, DIM);
            r.textMiddle(date(v.published()), x + 1320 * s, band, bandH, 2.3f * s, DIM);
            String dl = compact(v.downloads());
            r.textMiddle(dl, x + w - 50 * s - r.width(dl, 2.3f * s), band, bandH, 2.3f * s, DIM);
            hit(x + 20 * s, ry, w - 40 * s, rowH - 6 * s, () -> { zone = Zone.CONTENT; versionSelected = i + 1; },
                    Input.Action.CONFIRM);
        });
    }

    private void drawCrash(float s) {
        float x = 960 * s, y = 200 * s, w = 840 * s, pad = 30 * s, scale = 2.5f * s;
        List<String> lines = new ArrayList<>();
        for (String line : crash.lines()) lines.addAll(r.wrap(line, w - 2 * pad, scale));
        if (crash.source() != null) lines.addAll(r.wrap("Details: " + crash.source(), w - 2 * pad, scale));
        float h = 110 * s + lines.size() * 30 * s;
        r.roundRect(x, y, w, h, 12 * s, PANEL);
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
            boolean on = i == selected && !editor.active() && !systemKeyboardOpen;
            row(x, y - 10 * s, w, 60 * s, on);
            r.textMiddle(items.get(i), x + 34 * s, y - 10 * s, 60 * s, 3.4f * s, on ? TEXT : DIM);
            int index = i;
            hit(x, y - 10 * s, w, 60 * s, () -> selected = index, Input.Action.CONFIRM);
            y += 70 * s;
        }
    }

    /** A smoothly scrolled list of rows, at most visible rows high, following the selection on key moves. */
    private void list(List<String> rows, List<Integer> colors, float x, float y, float w, int visible) {
        float s = r.s, scale = 3 * s, rowH = 52 * s;
        scrollList(scrollers[page.ordinal()], rows.size(), selected, x, y - 8 * s, w, visible * rowH, rowH, (i, ry) -> {
            boolean on = i == selected && !editor.active() && !systemKeyboardOpen;
            row(x, ry, w, 46 * s, on);
            r.textMiddle(r.fit(rows.get(i), w - 48 * s, scale), x + 28 * s, ry, 46 * s, scale, on ? TEXT : colors.get(i));
            hit(x, ry, w, 46 * s, () -> selected = i, Input.Action.CONFIRM);
        });
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
