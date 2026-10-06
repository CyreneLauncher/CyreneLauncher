package orbis.launcher;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
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
 * Modrinth browser whose installs are checked for the console first (ModInstaller); and the
 * accounts with Microsoft sign-in by QR code. Network and file work runs on a background thread;
 * its results are applied on this, the GL thread.
 */
class LauncherScreen {
    private enum Page { INSTANCES, MODS, ACCOUNTS, SETTINGS, BROWSE, DETAIL }
    private enum Focus { LIST, DETAIL }
    private enum Field { SEARCH, INSTANCE_NAME, OFFLINE_NAME }
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
    private final Queue<Runnable> results = new ConcurrentLinkedQueue<>();
    private final ModrinthClient modrinth;
    private ModInstaller installer;
    private final ModInstaller suppliedInstaller;

    private long window;
    private Page page = Page.INSTANCES;
    private Focus focus = Focus.LIST;
    private final int[] selections = new int[Page.values().length];
    private final int[] scrolls = new int[Page.values().length];
    private int selected, scroll;
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

    // Accounts page.
    private AccountStore accounts;
    private MicrosoftAuthClient.DeviceSession login;
    private boolean[][] loginQr;
    private boolean loginPolling;
    private String accountRemovePending;

    // Mods page.
    private List<Path> mods = new ArrayList<>();
    // Browse and detail pages.
    private final StringBuilder query = new StringBuilder();
    private List<ModrinthClient.Hit> hits = List.of();
    private final TextEntry editor = new TextEntry();
    private Field editingField;
    private boolean systemKeyboardOpen;
    private final SystemKeyboard systemKeyboard = new SystemKeyboard();
    private Path removePending;
    private boolean removeConfirmed;
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
        this.suppliedInstaller = installer;
        String bundle = System.getProperty("runner.graphics.bundle");
        this.installer = installer != null ? installer
                : new ModInstaller(modrinth, config.modsDir(), bundle == null ? null : Path.of(bundle),
                        config.builtInVersions());
        List<InstanceStore.Instance> all = config.instances.list();
        for (int i = 0; i < all.size(); i++)
            if (all.get(i).id().equals(config.selectedInstance().id())) { selected = i; break; }
        if (!config.instances.recoveryWarning().isEmpty()) setStatus(config.instances.recoveryWarning(), WARN);
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
                    input.takeTyped(); // Compatibility buffer; live input uses callback-order events.
                    applyEvents("", input.events());
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
        if (editor.active() && !systemKeyboardOpen && !typed.isEmpty()) editor.insert(typed);
        for (Input.Event event : events) {
            if (event.action() == null) {
                if (editor.active() && !systemKeyboardOpen) editor.insert(event.text());
                continue;
            }
            Input.Action action = event.action();
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
                        || login != null) break;
            }
        }
    }

    private void beginEdit(Field field, Input.Source source) {
        editingField = field;
        String initial = field == Field.SEARCH ? query.toString()
                : field == Field.OFFLINE_NAME ? offlineName()
                : nameMode == NameMode.RENAME ? config.instances.get(nameTargetId).name()
                : nameMode == NameMode.CLONE ? config.instances.get(nameTargetId).name() + " Copy"
                : "New Minecraft";
        editor.begin(initial);
        input.releaseBarrier();
        if (source == Input.Source.CONTROLLER) {
            systemKeyboardOpen = field == Field.SEARCH ? beginSystemKeyboard("Search mods", 100)
                    : field == Field.OFFLINE_NAME ? beginSystemKeyboard("Offline player name", 16)
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
            if (accepted) search();
        } else if (editingField == Field.OFFLINE_NAME) {
            if (accepted) {
                try {
                    accounts().renameOffline(value.strip());
                    setStatus("Offline name is now " + offlineName() + ".", ACCENT);
                } catch (IOException | IllegalArgumentException failure) {
                    setStatus("Cannot use that name: " + failure.getMessage(), ERROR);
                }
            }
        } else if (accepted) {
            try {
                if (nameMode == NameMode.RENAME) {
                    config.instances.rename(nameTargetId, value);
                    setStatus("Instance renamed.", ACCENT);
                } else if (nameMode == NameMode.CREATE) {
                    startInstanceOperation("Creating instance...", new InstanceStore.Operation(),
                            op -> config.instances.create(value, op));
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
        r.begin(framebufferWidth, framebufferHeight);
        draw();
        r.end();
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
        scroll = 0;
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
            case SETTINGS -> 5;
            case ACCOUNTS -> accountList().size() + 2;
            case MODS -> 1 + mods.size();
            case BROWSE -> 1 + hits.size();
            case DETAIL -> plan != null && plan.installable() ? 2 : 1;
        };
    }

    private void go(Page next, int select) {
        selections[page.ordinal()] = selected;
        scrolls[page.ordinal()] = scroll;
        page = next;
        selected = select < 0 ? selections[next.ordinal()] : select;
        scroll = select < 0 ? scrolls[next.ordinal()] : 0;
        focus = Focus.LIST;
    }

    private void switchSection(int direction) {
        Page[] sections = {Page.INSTANCES, Page.MODS, Page.ACCOUNTS, Page.SETTINGS};
        int current = switch (page) {
            case INSTANCES -> 0;
            case MODS, BROWSE, DETAIL -> 1;
            case ACCOUNTS -> 2;
            case SETTINGS -> 3;
        };
        Page next = sections[(current + direction + sections.length) % sections.length];
        go(next, -1);
        if (next == Page.MODS) refreshMods();
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
                if (selected == 3) save();
                else if (selected == 4) back();
                else adjust(+1);
            }
            case MODS -> {
                if (selected == 0) { go(Page.BROWSE, -1); setStatus("", DIM); }
                else toggle(mods.get(selected - 1));
            }
            case BROWSE -> {
                if (selected == 0) beginEdit(Field.SEARCH, source);
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
            case INSTANCES -> focus = Focus.LIST;
            case SETTINGS -> {
                go(Page.INSTANCES, -1);
                if (dirty) setStatus("Unsaved changes - open Settings and choose Save", WARN);
            }
            case ACCOUNTS -> go(Page.INSTANCES, -1);
            case MODS -> go(Page.INSTANCES, -1);
            case BROWSE -> { go(Page.MODS, -1); refreshMods(); }
            case DETAIL -> { go(Page.BROWSE, -1); plan = null; }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ home and settings

    private void instanceAction(Input.Action action, Input.Source source) {
        List<InstanceStore.Instance> values = config.instances.list();
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
            case UP -> detailSelected = (detailSelected + 7) % 8;
            case DOWN -> detailSelected = (detailSelected + 1) % 8;
            case CONFIRM -> {
                InstanceStore.Instance current = config.selectedInstance();
                switch (detailSelected) {
                    case 0 -> play();
                    case 1 -> { nameMode = NameMode.CREATE; nameTargetId = null; beginEdit(Field.INSTANCE_NAME, source); }
                    case 2 -> { nameMode = NameMode.CLONE; nameTargetId = current.id(); beginEdit(Field.INSTANCE_NAME, source); }
                    case 3 -> { nameMode = NameMode.RENAME; nameTargetId = current.id(); beginEdit(Field.INSTANCE_NAME, source); }
                    case 4 -> {
                        if (config.instances.list().size() <= 1) setStatus("Keep at least one instance.", WARN);
                        else { instanceRemovePending = current.id(); removeConfirmed = false; }
                    }
                    case 5 -> { go(Page.MODS, -1); refreshMods(); setStatus("", DIM); }
                    case 6 -> { go(Page.SETTINGS, -1); setStatus("", DIM); }
                    default -> closeWindow();
                }
            }
            default -> { }
        }
    }

    private void chooseInstance(String id) throws IOException {
        if (dirty) config.save();
        generation++;
        config.selectInstance(id);
        dirty = false;
        resyncSelected();
        setStatus("Selected " + config.selectedInstance().name(), ACCENT);
    }

    /** Refreshes every cached instance-scoped screen object after a store transition. */
    private void resyncSelected() {
        config.refreshSelected();
        List<InstanceStore.Instance> values = config.instances.list();
        for (int i = 0; i < values.size(); i++)
            if (values.get(i).id().equals(config.selectedInstance().id())) { selected = i; break; }
        crash = config.lastSession();
        String bundle = System.getProperty("runner.graphics.bundle");
        installer = suppliedInstaller != null ? suppliedInstaller
                : new ModInstaller(modrinth, config.modsDir(), bundle == null ? null : Path.of(bundle),
                        config.builtInVersions());
        mods = new ArrayList<>();
        hits = List.of();
        plan = null;
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
                if (removeConfirmed) {
                    boolean managed = !config.instances.get(id).imported();
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
                                    : "Imported registration removed; game files kept.", ACCENT);
                        });
                    });
                }
                input.releaseBarrier();
            }
            default -> { }
        }
    }

    private void adjust(int direction) {
        if (page == Page.ACCOUNTS && selected == accountList().size() + 1) { cycleInstanceAccount(direction); return; }
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
        final InstanceStore.Instance instance = config.selectedInstance();
        background("Checking account before starting...", () -> {
            if (dirty) config.save();
            AccountStore.Account account = accounts().resolveAccount(instance.accountId());
            authClient().refresh(account.id());
            return new LaunchPreparation(config).prepare(config.instances.get(instance.id()), account.id());
        }, request -> {
            setStatus("Starting Minecraft...", ACCENT);
            presentNow();
            int error = relaunch(request.preparationId());
            try { new LaunchPreparation(config).rollback(); }
            catch (IOException recovery) { setStatus("Restart failed; runner settings require recovery", ERROR); return; }
            setStatus(String.format("Restart failed (0x%08x)", error), ERROR);
        });
    }

    // ------------------------------------------------------------------ accounts

    private List<AccountStore.Account> accountList() {
        try { return accounts().list(); }
        catch (IOException failure) { return List.of(); }
    }

    private String offlineName() {
        try { return accounts().get(AccountStore.OFFLINE_ID).username(); }
        catch (IOException failure) { return "Player"; }
    }

    private String accountName(String id) {
        if (id == null || id.isEmpty()) return "Default";
        try {
            AccountStore.Account account = accounts().get(id);
            return account == null ? "Missing account" : account.username()
                    + (AccountStore.OFFLINE_ID.equals(id) ? " (offline)" : "");
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
            setStatus(account.username() + " is now the default account.", ACCENT);
        } catch (IOException failure) { setStatus("Cannot change the default: " + failure.getMessage(), ERROR); }
    }

    private void manageAccount(Input.Source source) {
        List<AccountStore.Account> list = accountList();
        if (selected >= list.size()) return;
        AccountStore.Account account = list.get(selected);
        if (AccountStore.OFFLINE_ID.equals(account.id())) beginEdit(Field.OFFLINE_NAME, source);
        else { accountRemovePending = account.id(); removeConfirmed = false; }
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
            setStatus(config.selectedInstance().name() + " uses " + effectiveAccount(config.selectedInstance()) + ".", ACCENT);
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
                        setStatus("Signed out " + name + "; its saved sign-in was deleted.", ACCENT);
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
                if (failed == null) setStatus("Signed in as " + result.username() + ".", ACCENT);
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
                        setStatus("Removed " + target.getFileName(), ACCENT);
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

    // ------------------------------------------------------------------ browse and install

    private void search() {
        String q = query.toString().trim();
        background("Searching Modrinth for \"" + q + "\"...", true, () -> modrinth.search(q), found -> {
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
        ModInstaller target = installer;
        background("Downloading and checking " + hit.title() + "...", true,
                () -> target.plan(hit.projectId(), hit.title()),
                result -> {
                    plan = result;
                    selected = 0;
                    setStatus(result.installable() ? "Ready to install." : "This cannot be installed.",
                            result.installable() ? ACCENT : ERROR);
                });
    }

    private void install() {
        ModInstaller.Plan current = plan;
        ModInstaller target = installer;
        String into = config.selectedInstance().name();
        background("Installing " + current.entries().size() + " file(s) into " + into + "...", () -> {
            target.install(current);
            return current.entries().size();
        }, count -> {
            refreshMods();
            plan = null;
            go(Page.MODS, 0);
            setStatus("Installed " + chosen.title() + (count > 1 ? " and " + (count - 1) + " dependenc"
                    + (count == 2 ? "y" : "ies") : "") + ". Applies when you press Play.", ACCENT);
        });
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
        r.text("INSTANCES    MODS    ACCOUNTS    SETTINGS", 120 * s, 168 * s, 2.5f * s, DIM);
        InstanceStore.Instance active = config.selectedInstance();
        r.text(r.fit("Selected: " + active.name() + "  |  Account: " + effectiveAccount(active), 750 * s, 2.2f * s),
                1050 * s, 130 * s, 2.2f * s, DIM);
        switch (page) {
            case INSTANCES -> drawHome(s);
            case ACCOUNTS -> drawAccounts(s);
            case SETTINGS -> drawSettings(s);
            case MODS -> drawMods(s);
            case BROWSE -> drawBrowse(s);
            case DETAIL -> drawDetail(s);
        }

        if (removePending != null) drawRemoveDialog(s);
        if (instanceRemovePending != null) drawInstanceRemoveDialog(s);
        if (accountRemovePending != null) drawAccountRemoveDialog(s);
        if (login != null) drawLoginDialog(s);
        if (cloneOptions) drawCloneOptions(s);
        if (editor.active() && editingField == Field.INSTANCE_NAME) drawNameDialog(s);
        String shownStatus = instanceOperation == null ? status : instanceOperation.progress();
        if (instanceOperation != null && instanceOperation.destination() != null)
            r.text(r.fit("Destination: " + instanceOperation.destination(), r.width - 240 * s, 2.2f * s),
                    120 * s, r.height - 188 * s, 2.2f * s, DIM);
        if (!shownStatus.isEmpty())
            r.text(r.fit(shownStatus, r.width - 240 * s, 3 * s), 120 * s, r.height - 150 * s, 3 * s, statusColor);
        r.text(hint(), 120 * s, r.height - 90 * s, 2.5f * s, DIM);
    }

    private String hint() {
        if (removePending != null) return "Left/Right: choice   Cross/Enter: accept   Circle/Escape: cancel";
        if (instanceRemovePending != null) return "Left/Right: choice   Cross/Enter: accept   Circle/Escape: cancel";
        if (accountRemovePending != null) return "Left/Right: choice   Cross/Enter: accept   Circle/Escape: cancel";
        if (login != null) return "Circle/Escape: cancel sign-in";
        if (busy && cancellable) return "Circle/Escape: cancel";
        if (cloneOptions) return "Up/Down: choice   Cross/Enter: toggle or continue   Circle/Escape: cancel";
        if (instanceOperation != null) return "Copying files   Circle/Escape: cancel";
        if (systemKeyboardOpen) return systemKeyboard.cancelling()
                ? "Closing system keyboard..." : "System keyboard: confirm or cancel";
        if (editor.active()) return "Type: edit   Arrows/Home/End: caret   Enter: accept   Escape: cancel";
        return switch (page) {
            case MODS -> "Cross: open / enable-disable   Triangle: remove   Circle: back   L1/R1: section";
            case BROWSE -> "Cross/Enter: edit search / details   Circle/Escape: back   L1/R1: section";
            case INSTANCES -> "Cross/Enter: select   Left/Right or Tab: focus   L1/R1: section";
            case ACCOUNTS -> "Cross: make default / choose   Triangle: rename or sign out   Circle: back   L1/R1: section";
            default -> "Cross/Enter: select   Circle/Escape: back   L1/R1: section";
        };
    }

    private void drawHome(float s) {
        r.text("INSTANCES", 120 * s, 215 * s, 4 * s, TEXT);
        List<InstanceStore.Instance> values = config.instances.list();
        if (selected >= values.size()) selected = values.size() - 1;
        int visible = 10;
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + visible) scroll = selected - visible + 1;
        for (int i = scroll; i < Math.min(values.size(), scroll + visible); i++) {
            InstanceStore.Instance value = values.get(i);
            float y = (280 + (i - scroll) * 60) * s;
            boolean highlighted = focus == Focus.LIST && selected == i && !editor.active();
            if (highlighted) r.rect(120 * s, y - 8 * s, 750 * s, 53 * s, ACCENT);
            String label = (value.id().equals(config.selectedInstance().id()) ? "* " : "  ") + value.name();
            r.text(r.fit(label, 710 * s, 3 * s), 140 * s, y, 3 * s, highlighted ? DARK : TEXT);
        }
        InstanceStore.Instance active = config.selectedInstance();
        float x = 930 * s;
        r.rect(x, 205 * s, 870 * s, 250 * s, PANEL);
        r.rect(x, 205 * s, 8 * s, 250 * s, ACCENT);
        r.text(r.fit(active.name(), 800 * s, 4 * s), x + 35 * s, 230 * s, 4 * s, TEXT);
        r.text(active.imported() ? "Imported installation" : "Managed installation", x + 35 * s, 295 * s, 2.6f * s, DIM);
        r.text(r.fit(active.gameDir().toString(), 790 * s, 2.3f * s), x + 35 * s, 330 * s, 2.3f * s, DIM);
        r.text(config.modCount() + " mods  |  Heap " + config.heapMib + " MiB  |  GPU " + config.arenaMib + " MiB",
                x + 35 * s, 375 * s, 2.5f * s, DIM);
        if (crash != null) r.text(r.fit("Last session: " + crash.title(), 790 * s, 2.2f * s),
                x + 35 * s, 410 * s, 2.2f * s, WARN);
        List<String> actions = List.of("Play", "Create instance", "Clone selected", "Rename selected",
                "Remove selected", "Mods", "Settings", "Quit");
        for (int i = 0; i < actions.size(); i++) {
            float y = (480 + i * 52) * s;
            boolean highlighted = focus == Focus.DETAIL && detailSelected == i && !editor.active();
            if (highlighted) r.rect(x, y - 7 * s, 870 * s, 48 * s, ACCENT);
            r.text(actions.get(i), x + 30 * s, y, 3 * s, highlighted ? DARK : i == 0 ? ACCENT : TEXT);
        }
    }

    private void drawNameDialog(float s) {
        float x = 350 * s, y = 330 * s;
        r.rect(x, y, 1220 * s, 320 * s, PANEL_HI);
        r.text(nameMode == NameMode.CREATE ? "CREATE INSTANCE" : nameMode == NameMode.CLONE
                ? "CLONE INSTANCE" : "RENAME INSTANCE", x + 40 * s, y + 35 * s, 3.5f * s, TEXT);
        String value = editor.value();
        String caret = value.substring(0, editor.caret()) + "|" + value.substring(editor.caret());
        r.text(r.fit(caret, 1120 * s, 3.5f * s), x + 40 * s, y + 115 * s, 3.5f * s, ACCENT);
        r.text("Enter/Cross: accept     Escape/Circle: cancel", x + 40 * s, y + 230 * s, 2.5f * s, DIM);
    }

    private void drawCloneOptions(float s) {
        float x = 410 * s, y = 350 * s;
        r.rect(x, y, 1100 * s, 340 * s, PANEL_HI);
        r.text("CLONE " + pendingCloneName, x + 35 * s, y + 30 * s, 3.5f * s, TEXT);
        List<String> rows = List.of("Copy worlds: " + (copyWorlds ? "[x]" : "[ ]"), "Create clone", "Cancel");
        for (int i = 0; i < rows.size(); i++) {
            float rowY = y + (100 + i * 70) * s;
            if (i == cloneOptionSelected) r.rect(x + 30 * s, rowY - 8 * s, 900 * s, 55 * s, ACCENT);
            r.text(rows.get(i), x + 55 * s, rowY, 3 * s, i == cloneOptionSelected ? DARK : TEXT);
        }
    }

    private void drawInstanceRemoveDialog(float s) {
        InstanceStore.Instance value = config.instances.get(instanceRemovePending);
        if (value == null) return;
        float x = 380 * s, y = 340 * s;
        r.rect(x, y, 1160 * s, 350 * s, PANEL_HI);
        r.text(r.fit("Remove " + value.name() + "?", 1080 * s, 3.5f * s), x + 35 * s, y + 35 * s, 3.5f * s, TEXT);
        r.text(value.imported() ? "Only its registration will be removed. Game files stay in place."
                : "Its mods, configuration, and worlds will be deleted.", x + 35 * s, y + 115 * s, 2.5f * s, WARN);
        r.rect(x + 35 * s, y + 235 * s, 360 * s, 60 * s, removeConfirmed ? PANEL : ACCENT);
        r.text("Cancel", x + 60 * s, y + 245 * s, 3 * s, removeConfirmed ? TEXT : DARK);
        r.rect(x + 445 * s, y + 235 * s, 360 * s, 60 * s, removeConfirmed ? ERROR : PANEL);
        r.text("Remove", x + 470 * s, y + 245 * s, 3 * s, removeConfirmed ? TEXT : DIM);
    }

    private void drawAccounts(float s) {
        r.text("ACCOUNTS", 120 * s, 210 * s, 4 * s, TEXT);
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
                    + (AccountStore.OFFLINE_ID.equals(account.id()) ? "   (offline)" : "   (Microsoft)")
                    + (isDefault ? "   default" : ""));
            colors.add(TEXT);
        }
        rows.add("Add Microsoft account");
        colors.add(ACCENT);
        InstanceStore.Instance active = config.selectedInstance();
        rows.add("Account for " + active.name() + ":  < "
                + (active.accountId().isEmpty() ? "Follow default" : accountName(active.accountId())) + " >");
        colors.add(TEXT);
        list(rows, colors, 120 * s, 280 * s, 1680 * s, 12);
    }

    private void drawLoginDialog(float s) {
        MicrosoftAuthClient.DeviceSession session = login;
        float x = 260 * s, y = 230 * s, w = 1400 * s, h = 640 * s;
        r.rect(x, y, w, h, PANEL_HI);
        r.text("SIGN IN WITH MICROSOFT", x + 40 * s, y + 35 * s, 3.5f * s, TEXT);
        if (loginQr != null) LoginQr.draw(r, loginQr, x + 40 * s, y + 100 * s, 480 * s);
        float tx = x + 580 * s;
        r.text("1. Scan the code or open:", tx, y + 110 * s, 2.6f * s, DIM);
        r.text(r.fit(session.verificationUri(), w - 620 * s, 3.2f * s), tx, y + 150 * s, 3.2f * s, ACCENT);
        r.text("2. Enter this code:", tx, y + 230 * s, 2.6f * s, DIM);
        r.text(session.userCode(), tx, y + 275 * s, 6 * s, TEXT);
        long left = Math.max(0, Duration.between(Instant.now(), session.expiresAt()).getSeconds());
        r.text(String.format("Code expires in %d:%02d", left / 60, left % 60), tx, y + 400 * s, 2.6f * s,
                left < 60 ? WARN : DIM);
        r.text("3. Sign in with the account that owns Minecraft.", tx, y + 450 * s, 2.6f * s, DIM);
        r.text("Circle/Escape: cancel", tx, y + 560 * s, 2.6f * s, DIM);
    }

    private void drawAccountRemoveDialog(float s) {
        float x = 380 * s, y = 340 * s;
        r.rect(x, y, 1160 * s, 350 * s, PANEL_HI);
        r.text(r.fit("Sign out " + accountName(accountRemovePending) + "?", 1080 * s, 3.5f * s),
                x + 35 * s, y + 35 * s, 3.5f * s, TEXT);
        r.text("The saved sign-in is deleted. Instances set to it will ask for another.", x + 35 * s, y + 115 * s,
                2.5f * s, WARN);
        r.rect(x + 35 * s, y + 235 * s, 360 * s, 60 * s, removeConfirmed ? PANEL : ACCENT);
        r.text("Cancel", x + 60 * s, y + 245 * s, 3 * s, removeConfirmed ? TEXT : DARK);
        r.rect(x + 445 * s, y + 235 * s, 360 * s, 60 * s, removeConfirmed ? ERROR : PANEL);
        r.text("Sign out", x + 470 * s, y + 245 * s, 3 * s, removeConfirmed ? TEXT : DIM);
    }

    private void drawSettings(float s) {
        r.text("SETTINGS", 120 * s, 210 * s, 4 * s, TEXT);
        menu(List.of("Java heap        < " + config.heapMib + " MiB >",
                "GPU memory       < " + config.arenaMib + " MiB >",
                "Runner debug log (global)  < " + (config.debugLog ? "on" : "off") + " >", "Save", "Back"),
                120 * s, 280 * s, 760 * s);
    }

    private void drawMods(float s) {
        long on = mods.stream().filter(LauncherScreen::enabled).count();
        r.text(r.fit("MODS  (" + on + " enabled, " + (mods.size() - on) + " disabled)  -  "
                + config.selectedInstance().name(), 1680 * s, 4 * s), 120 * s, 210 * s, 4 * s, TEXT);
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
        String shown = editor.active() && editingField == Field.SEARCH ? editor.value() : query.toString();
        if (editor.active() && editingField == Field.SEARCH)
            shown = shown.substring(0, editor.caret()) + "|" + shown.substring(editor.caret());
        rows.add("Search: " + shown);
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

    private void drawRemoveDialog(float s) {
        float x = 400 * s, y = 350 * s;
        r.rect(x, y, 1120 * s, 320 * s, PANEL_HI);
        r.text("Remove " + removePending.getFileName() + "?", x + 40 * s, y + 50 * s, 3.5f * s, TEXT);
        r.text("The mod file will be deleted.", x + 40 * s, y + 125 * s, 3 * s, WARN);
        r.rect(x + 40 * s, y + 220 * s, 340 * s, 60 * s, removeConfirmed ? PANEL : ACCENT);
        r.text("Cancel", x + 70 * s, y + 230 * s, 3.5f * s, removeConfirmed ? TEXT : DARK);
        r.rect(x + 420 * s, y + 220 * s, 340 * s, 60 * s, removeConfirmed ? ERROR : PANEL);
        r.text("Remove", x + 450 * s, y + 230 * s, 3.5f * s, removeConfirmed ? TEXT : DIM);
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
            boolean on = i == selected && !editor.active() && !systemKeyboardOpen;
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
            boolean on = i == selected && !editor.active() && !systemKeyboardOpen;
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
