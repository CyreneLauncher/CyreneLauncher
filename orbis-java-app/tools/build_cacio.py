#!/usr/bin/env python3
"""Build the pinned, JVM-only Cacio source bundle. No Maven or runtime downloads."""
import argparse, hashlib, json, os, pathlib, re, shutil, subprocess, tarfile, tempfile, urllib.request, uuid, zipfile

HERE = pathlib.Path(__file__).resolve().parent
PROJECT = HERE.parent
SPEC = json.loads((HERE / 'cacio-source.json').read_text())

# Exports needed by Cacio on JDK 17; kept explicit and recorded in manifest.
EXPORTS = [
 'java.desktop/sun.awt=ALL-UNNAMED','java.desktop/sun.awt.datatransfer=ALL-UNNAMED',
 'java.desktop/sun.awt.event=ALL-UNNAMED','java.desktop/sun.awt.image=ALL-UNNAMED',
 'java.desktop/sun.font=ALL-UNNAMED','java.desktop/sun.java2d=ALL-UNNAMED',
 'java.desktop/sun.java2d.pipe=ALL-UNNAMED','java.desktop/java.awt.peer=ALL-UNNAMED',
 'java.desktop/java.awt.dnd.peer=ALL-UNNAMED','java.base/sun.security.action=ALL-UNNAMED'
]
OPENS = ['java.desktop/java.awt=ALL-UNNAMED']

def expected_launch_options():
    return [
        '-Djava.awt.headless=false',
        '-Dcacio.managed.screensize=1280x720',
        '-Dorbis.awt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit',
        '-Dorbis.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment',
        '-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel',
        *('--add-exports='+value for value in EXPORTS),
        *('--add-opens='+value for value in OPENS),
    ]

def safe_extract(archive, dest):
    root = pathlib.Path(dest).resolve()
    with tarfile.open(archive, 'r:gz') as tf:
        for m in tf.getmembers():
            p = (root / m.name).resolve()
            if p != root and root not in p.parents: raise ValueError('archive traversal: '+m.name)
            if m.issym() or m.islnk(): raise ValueError('archive link rejected: '+m.name)
        tf.extractall(root, filter='data')

def checked_path(value, label):
    path = pathlib.Path(os.path.abspath(os.fspath(value)))
    current = pathlib.Path(path.anchor)
    for part in path.parts[1:]:
        current = current/part
        if current.is_symlink(): raise ValueError(f'{label} path contains symlink: {current}')
    return path

def prepare():
    build_root = checked_path(PROJECT / 'build-cacio', 'prepare')
    build_root.mkdir(parents=True, exist_ok=True)
    build_root = checked_path(build_root, 'prepare')
    target = build_root / 'source'
    checked_path(target, 'prepared source')
    fd, name = tempfile.mkstemp(prefix='cacio-', suffix='.tar.gz')
    os.close(fd)
    stage = pathlib.Path(tempfile.mkdtemp(prefix='.source.staged-', dir=build_root))
    try:
        urllib.request.urlretrieve(SPEC['archive'], name)
        digest = hashlib.sha256(pathlib.Path(name).read_bytes()).hexdigest()
        if digest != SPEC['sha256']: raise ValueError(f'archive SHA256 mismatch: {digest}')
        safe_extract(name, stage)
        (stage / 'ARCHIVE.sha256').write_text(digest+'  caciocavallo.tar.gz\n')
        shutil.copy2(name, stage/'caciocavallo.tar.gz')
        backup = None
        if target.exists():
            backup = build_root/('.source.backup-'+uuid.uuid4().hex)
            target.rename(backup)
        try:
            stage.rename(target)
        except Exception:
            if backup is not None and backup.exists(): backup.rename(target)
            raise
        if backup is not None:
            if backup.is_dir(): shutil.rmtree(backup)
            else: backup.unlink()
    except Exception:
        if stage.exists(): shutil.rmtree(stage)
        raise
    finally: pathlib.Path(name).unlink(missing_ok=True)

def audit_bundle(stage, jdk):
    if any(p.is_symlink() for p in stage.rglob('*')):
        raise ValueError('symlink in staged runtime export')
    expected = {'cacio-shared.jar', 'cacio-tta.jar', 'orbis-desktop-adapter.jar'}
    if {p.name for p in (stage/'lib').iterdir()} != expected:
        raise ValueError('export must contain exactly the three runtime JARs')
    required = {'LICENSE', 'build-manifest.json', 'launch-options.txt'}
    if {p.name for p in stage.iterdir()} != required | {'lib'}:
        raise ValueError('export contains missing or unexpected top-level files')
    manifest = json.loads((stage/'build-manifest.json').read_text())
    if manifest.get('schema') != 1:
        raise ValueError('unsupported build manifest schema')
    if (manifest.get('repository') != SPEC['repository'] or manifest.get('commit') != SPEC['commit']
            or manifest.get('archive_sha256') != SPEC['sha256'] or manifest.get('license') != SPEC['license']):
        raise ValueError('build manifest provenance does not match pinned source metadata')
    if manifest.get('exports') != EXPORTS or manifest.get('opens') != OPENS:
        raise ValueError('build manifest exports/opens do not match the pinned JDK access contract')
    if manifest.get('bridge_abi') != 1 or manifest.get('packet_word_count') != 24:
        raise ValueError('build manifest bridge ABI/packet contract is stale')
    expected_hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                       for p in sorted((stage/'lib').glob('*.jar'))}
    if manifest.get('jar_sha256') != expected_hashes:
        raise ValueError('build manifest JAR SHA256 inventory does not match staged artifacts')
    if not (stage/'LICENSE').is_file() or (stage/'LICENSE').stat().st_size == 0:
        raise ValueError('upstream LICENSE is missing or empty')
    options = (stage/'launch-options.txt').read_text().splitlines()
    if options != expected_launch_options():
        raise ValueError('launch options do not match the exact provider, screen, Metal, exports, and opens contract')
    seen = set()
    required_classes = {
        'com/github/caciocavallosilano/cacio/ctc/CTCToolkit.class',
        'com/github/caciocavallosilano/cacio/ctc/CTCGraphicsEnvironment.class',
        'com/github/caciocavallosilano/cacio/ctc/CTCScreen.class',
        'com/github/caciocavallosilano/cacio/ctc/CacioLocalClipboard.class',
        'com/github/caciocavallosilano/cacio/ctc/SynchronizedGraphics2D.class',
        'runner/desktop/DesktopBootstrap.class',
        'runner/desktop/DesktopSession.class',
        'runner/desktop/DesktopRuntime.class',
        'runner/desktop/DesktopInputAdapter.class',
        'runner/desktop/NativeDesktopBridge.class',
    }
    found = set()
    forbidden = re.compile(r'(^|/)(android|junit|org/junit|org/hamcrest|pojav|libpojavexec)', re.I)
    native_suffix = re.compile(r'\.(so(?:\..*)?|dll(?:\..*)?|dylib|jnilib|lib|a|o|prx|sprx|elf|oelf)$', re.I)
    native_magic = (
        b'\x7fELF', b'O\x15=\x1d', b'MZ',
        b'\xfe\xed\xfa\xce', b'\xce\xfa\xed\xfe',
        b'\xfe\xed\xfa\xcf', b'\xcf\xfa\xed\xfe',
        b'\xca\xfe\xba\xbe', b'\xbe\xba\xfe\xca',
    )
    for jar in sorted((stage/'lib').glob('*.jar')):
        with zipfile.ZipFile(jar) as archive:
            for info in archive.infolist():
                name = info.filename
                if name.endswith('/'):
                    continue
                if info.external_attr >> 28 & 0xF == 0xA:
                    raise ValueError(f'symlink in runtime JAR: {jar.name}:{name}')
                if re.search(r'(^|/)(test|tests|junit|android)(/|$)', name, re.I):
                    raise ValueError(f'test/Android content in runtime JAR: {jar.name}:{name}')
                if forbidden.search(name): raise ValueError(f'test/Android bridge dependency in runtime JAR: {jar.name}:{name}')
                if native_suffix.search(name): raise ValueError(f'native asset in runtime JAR: {jar.name}:{name}')
                if name.endswith('.class'):
                    if name in seen: raise ValueError(f'duplicate shipped class: {name}')
                    seen.add(name); found.add(name)
                    data = archive.read(info)
                    if len(data) < 8 or data[:4] != b'\xca\xfe\xba\xbe': raise ValueError(f'invalid class file: {jar.name}:{name}')
                    for marker in (b'android/', b'org/junit/', b'org/hamcrest/', b'pojavexec', b'libpojavexec'):
                        if marker in data.lower(): raise ValueError(f'forbidden test/Android bridge reference in class: {jar.name}:{name}')
                    version = int.from_bytes(data[6:8], 'big')
                    if version > 61: raise ValueError(f'class file newer than Java 17: {jar.name}:{name} ({version})')
                else:
                    with archive.open(info) as content:
                        signature = content.read(4)
                    if any(signature.startswith(magic) for magic in native_magic):
                        raise ValueError(f'native binary signature in runtime JAR: {jar.name}:{name}')
    missing = required_classes-found
    if missing: raise ValueError('required runtime classes missing: '+', '.join(sorted(missing)))
    if not pathlib.Path(jdk, 'bin', 'javac').is_file(): raise ValueError('build JDK evidence is incomplete')

def replace_once(text, old, new, label):
    if text.count(old) != 1: raise ValueError(f'expected one source hunk for {label}, found {text.count(old)}')
    return text.replace(old, new, 1)

def patched_copy(src, stage):
    shutil.copytree(src, stage, symlinks=False)
    tta = stage/'cacio-tta/src/main/java/com/github/caciocavallosilano/cacio/ctc'
    screen = tta/'CTCScreen.java'
    text = screen.read_text()
    initializer = '''    static {
        // Load it to get JavaVM instance
        // System.loadLibrary("pojavexec");

        try {
            File currLibFile;
            for (String ldLib : System.getenv("LD_LIBRARY_PATH").split(":")) {
                if (ldLib.isEmpty()) continue;
                currLibFile = new File(ldLib, "libpojavexec_awt.so");
                if (currLibFile.exists()) {
                    System.load(currLibFile.getAbsolutePath());
                    break;
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }
'''
    if not text.endswith(initializer + '}\n'):
        raise ValueError('expected complete final CTCScreen Android initializer and no following members')
    text = replace_once(text, initializer, '', 'complete CTCScreen Android initializer')
    text = replace_once(text, '    private static CTCScreen instance;\n',
                        '    private static volatile CTCScreen instance;\n',
                        'safely published screen singleton')
    text = replace_once(text, '''    static CTCScreen getInstance() {
        if (instance == null) {
            instance = new CTCScreen();
        }
        return instance;
    }''', '''    static synchronized CTCScreen getInstance() {
        if (instance == null) {
            instance = new CTCScreen();
        }
        return instance;
    }

    /** Explicitly initialize and validate the fixed Orbis virtual screen. */
    public static synchronized void initializeScreen() {
        CTCScreen screen = getInstance();
        Dimension size = FullScreenWindowFactory.getScreenDimension();
        if (size.width != 1280 || size.height != 720
                || screen.screenBuffer.getWidth() != size.width
                || screen.screenBuffer.getHeight() != size.height) {
            throw new IllegalStateException("Cacio screen must be exactly 1280x720");
        }
    }''', 'safe explicit screen initialization')
    # Add the complete per-operation Graphics2D serialization boundary below
    # WindowClippedGraphics, keeping every clone on the same screen lock.
    shutil.copy2(PROJECT/'cacio/patches/SynchronizedGraphics2D.java',
                 tta/'SynchronizedGraphics2D.java')
    text = replace_once(text, '    private BufferedImage screenBuffer;\n',
                        '    private BufferedImage screenBuffer;\n    private final Object screenLock = new Object();\n    private long publicationSequence;\n    private boolean frameSubmissionClosed;\n',
                        'screen-owned graphics/snapshot lock')
    start = text.index('    int[] getRGBPixels(Rectangle bounds) {')
    end = text.rfind('\n}')
    if end < start: raise ValueError('missing CTCScreen class close after pixel helper')
    old = text[start:end]
    replacement = '''    int[] getRGBPixels(Rectangle bounds) {
        synchronized (screenLock) {
            return screenBuffer.getRGB(bounds.x, bounds.y, bounds.width, bounds.height,
                    null, 0, bounds.width);
        }
    }

    /** Copy and sequence one canonical caller-owned ARGB frame under screenLock. */
    public static long copyCurrentScreenARGB(int[] destination) {
        if (destination == null || destination.length != 1280 * 720) {
            throw new IllegalArgumentException("destination must contain exactly 921600 pixels");
        }
        CTCScreen screen = instance;
        if (screen == null || screen.screenBuffer == null
                || screen.screenBuffer.getWidth() != 1280
                || screen.screenBuffer.getHeight() != 720) {
            throw new IllegalStateException("Cacio screen is not initialized at 1280x720");
        }
        synchronized (screen.screenLock) {
            if (screen.frameSubmissionClosed) {
                throw new IllegalStateException("Desktop session is closed");
            }
            if (screen.publicationSequence == Long.MAX_VALUE) {
                throw new IllegalStateException("desktop publication sequence exhausted");
            }
            screen.screenBuffer.getRGB(0, 0, 1280, 720, destination, 0, 1280);
            return ++screen.publicationSequence;
        }
    }

    /** Close snapshot submission without affecting Robot or screen painting. */
    public static void closeFrameSubmission() {
        CTCScreen screen = instance;
        if (screen == null || screen.screenBuffer == null) {
            throw new IllegalStateException("Cacio screen is not initialized at 1280x720");
        }
        synchronized (screen.screenLock) {
            screen.frameSubmissionClosed = true;
        }
    }

'''
    text = text[:start] + replacement + text[end:]
    old_graphics = '''        Graphics2D g2d = (Graphics2D) screenBuffer.getGraphics();
        if (clipRects != null && clipRects.size() > 0) {
            Area a = new Area(getBounds());
            for (Rectangle clip : clipRects) {
                a.subtract(new Area(clip));
            }
            g2d = new WindowClippedGraphics(g2d, a);
        }
        return g2d;'''
    new_graphics = '''        Graphics2D g2d;
        synchronized (screenLock) {
            g2d = new SynchronizedGraphics2D(screenBuffer.createGraphics(), screenLock);
        }
        if (clipRects != null && clipRects.size() > 0) {
            Area a = new Area(getBounds());
            for (Rectangle clip : clipRects) {
                a.subtract(new Area(clip));
            }
            g2d = new WindowClippedGraphics(g2d, a);
        }
        // Outer serialization makes inherited convenience drawing methods one
        // operation even when WindowClippedGraphics expands them into primitives.
        return new SynchronizedGraphics2D(g2d, screenLock);'''
    text = replace_once(text, old_graphics, new_graphics, 'lock-sharing clipped graphics')
    text = replace_once(text, '    private CTCScreen() {', """    /** Event routing root only; never takes the drawing/snapshot lock. */
    public static com.github.caciocavallosilano.cacio.peer.managed.ScreenManagedWindowContainer getEventRoot() {
        CTCToolkit toolkit = (CTCToolkit) java.awt.Toolkit.getDefaultToolkit();
        FullScreenWindowFactory factory = (FullScreenWindowFactory) toolkit.getPlatformWindowFactory();
        return factory.getScreenManagedWindowContainer(getInstance());
    }

    private CTCScreen() {""", 'managed event root hook')
    screen.write_text(text)
    patch_desktop_events(stage, tta)
    toolkit = tta/'CTCToolkit.java'
    text = replace_once(toolkit.read_text(), 'return CTCClipboard.INSTANCE;', 'return CacioLocalClipboard.INSTANCE;', 'local clipboard')
    text = replace_once(text, '''    public ColorModel getColorModel() throws HeadlessException {
        // TODO Auto-generated method stub
        return null;
    }''', '''    public ColorModel getColorModel() throws HeadlessException {
        return CTCScreen.getInstance().getColorModel();
    }''', 'screen color model')
    toolkit.write_text(text)
    (tta/'CTCClipboard.java').unlink(missing_ok=True)
    (tta/'CTCPreloadClassLoader.java').unlink(missing_ok=True)
    (tta/'FontManagerUtil.java').unlink(missing_ok=True)
    shared_toolkit = stage/'cacio-shared/src/main/java/com/github/caciocavallosilano/cacio/peer/CacioToolkit.java'
    ctext = replace_once(shared_toolkit.read_text(), '''        if (isLinux()) {
            System.setProperty("sun.font.fontmanager", "sun.awt.FcFontManager" /*"sun.awt.peer.cacio.CacioFontManager"*/);
        }''', '''        // Keep the platform font manager selected by the JDK.''', 'preserve platform font manager')
    shared_toolkit.write_text(ctext)
    desktop = tta/'CTCDesktopPeer.java'
    dtext = desktop.read_text()
    dtext = replace_once(dtext, '''  public void open(File file) throws IOException {\n    //openFile(file.getAbsolutePath());\n  }''', '''  public void open(File file) throws IOException {\n    throw new IOException(\"Opening external files is unsupported\");\n  }''', 'unsupported Desktop.open')
    dtext = replace_once(dtext, '''  public void browse(URI uri) throws IOException {\n    //openUri(uri.toString());\n  }''', '''  public void browse(URI uri) throws IOException {\n    throw new IOException(\"Opening external URLs is unsupported\");\n  }''', 'unsupported Desktop.browse')
    dtext = replace_once(dtext, """            case APP_QUIT_HANDLER:
            case APP_ABOUT:
                return true;""", """            case APP_QUIT_HANDLER:
            case APP_ABOUT:
                return false;""", 'unsupported Desktop handlers')
    dtext = replace_once(dtext, """    public void setAboutHandler(final AboutHandler handler) {
        // Stub
    }""", """    public void setAboutHandler(final AboutHandler handler) {
        throw new UnsupportedOperationException("About handler is unsupported");
    }""", 'unsupported Desktop.setAboutHandler')
    dtext = replace_once(dtext, """    public void setQuitHandler(final QuitHandler handler) {
        // Stub
    }""", """    public void setQuitHandler(final QuitHandler handler) {
        throw new UnsupportedOperationException("Quit handler is unsupported");
    }""", 'unsupported Desktop.setQuitHandler')
    desktop.write_text(dtext)
    local = tta/'CacioLocalClipboard.java'
    local.write_text('''package com.github.caciocavallosilano.cacio.ctc;\n\nimport java.awt.datatransfer.Clipboard;\n\n/** Per-JVM clipboard; copy and paste are local to this application. */\nfinal class CacioLocalClipboard {\n    static final Clipboard INSTANCE = new Clipboard("Cacio local clipboard");\n    private CacioLocalClipboard() {}\n}\n''')
    return stage

def patch_desktop_events(stage, tta):
    source = tta/'CTCEventSource.java'
    text = source.read_text()
    text = replace_once(text, 'import java.util.concurrent.LinkedBlockingQueue;',
                        'import java.util.concurrent.LinkedBlockingQueue;\nimport java.util.concurrent.TimeUnit;\nimport java.awt.event.PaintEvent;', 'bounded queue imports')
    text = replace_once(text, '    private static CTCEventSource instance;',
                        '    private static CTCEventSource instance;\n    private static volatile CacioEventSource delegate;\n\n    /** Finite polling keeps local paint work and late source installation observable. */\n    public interface PollingEventSource extends CacioEventSource {\n        EventData pollNextEvent(int timeoutMillis) throws InterruptedException;\n    }\n\n    public static synchronized void installDesktopSource(PollingEventSource source) {\n        if (source == null) throw new NullPointerException("source");\n        if (delegate != null) throw new IllegalStateException("Desktop source already installed");\n        delegate = source;\n    }', 'optional volatile delegate')
    text = replace_once(text, '    static CTCEventSource getInstance()',
                        '    static synchronized CTCEventSource getInstance()', 'event source singleton')
    text = replace_once(text, 'new LinkedBlockingQueue<EventData>()',
                        'new LinkedBlockingQueue<EventData>(255)', 'bounded local queue')
    text = replace_once(text, '    private CTCEventSource() {', '    private EventData overflowPaint;\n\n    private CTCEventSource() {', 'single reserved paint invalidation slot')
    text = replace_once(text, '        return queue.take();', """        while (true) {
            EventData local = pollLocal();
            if (local != null) return local;
            CacioEventSource input = delegate;
            if (input != null) {
                EventData event = ((PollingEventSource) input).pollNextEvent(2);
                if (event != null) return event;
            }
        }""", 'finite source visibility and native poll')
    text = replace_once(text, """    void postEvent(EventData ev) {
        queue.offer(ev);
    }""", """    private synchronized EventData pollLocal() throws InterruptedException {
        if (overflowPaint != null) {
            EventData paint = overflowPaint;
            overflowPaint = null;
            return paint;
        }
        return queue.poll(2, TimeUnit.MILLISECONDS);
    }

    synchronized void postEvent(EventData ev) {
        // Coalesce same-root invalidation to the union, preserving its queue slot.
        if (ev.getId() == PaintEvent.PAINT || ev.getId() == PaintEvent.UPDATE) {
            for (EventData retained : queue) {
                if (retained.getSource() == ev.getSource()
                        && retained.getId() == ev.getId()) {
                    java.awt.Rectangle old = retained.getUpdateRect();
                    java.awt.Rectangle next = ev.getUpdateRect();
                    if (old != null && next != null) retained.setUpdateRect(old.union(next));
                    return;
                }
            }
        }
        if (!queue.offer(ev)) {
            if ((ev.getId() == PaintEvent.PAINT || ev.getId() == PaintEvent.UPDATE)
                    && ev.getSource() == CTCScreen.getInstance()) {
                // The single virtual screen gets a full invalidation in the reserved
                // slot. 255 queue entries + this one event never exceeds 256.
                ev.setUpdateRect(CTCScreen.getInstance().getBounds());
                overflowPaint = ev;
                return;
            }
            // Robot input overflow is explicit; never silently discard a release.
            throw new IllegalStateException("Cacio local event queue exhausted");
        }
    }""", 'bounded paint coalescing and explicit overflow')
    source.write_text(text)
    shared = stage/'cacio-shared/src/main/java/com/github/caciocavallosilano/cacio/peer'
    pump = shared/'CacioEventPump.java'
    text = replace_once(pump.read_text(), """    public final void run() {

        AWTAutoShutdown.notifyToolkitThreadBusy();
        while (true) {
            // Jump out if we get interrupted.
            if (Thread.interrupted()) {
                return;
            }
            try {
                AWTAutoShutdown.notifyToolkitThreadFree();
                ET nativeEvent = fetchNativeEvent();
                AWTAutoShutdown.notifyToolkitThreadBusy();
                dispatchNativeEvent(nativeEvent);
            } catch (Exception ex) {
                // Print stack trace but don't kill the pump.
                ex.printStackTrace();
            }
        }
    }""", """    public final void run() {
        try {
            AWTAutoShutdown.notifyToolkitThreadBusy();
            while (true) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    AWTAutoShutdown.notifyToolkitThreadFree();
                    ET nativeEvent = fetchNativeEvent();
                    AWTAutoShutdown.notifyToolkitThreadBusy();
                    dispatchNativeEvent(nativeEvent);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    // Preserve upstream recovery for other dispatch failures.
                    ex.printStackTrace();
                }
            }
        } finally {
            // Clear busy on every exit, including interruption during dispatch
            // and uncaught errors. Do not consume the thread's interrupt flag.
            AWTAutoShutdown.notifyToolkitThreadFree();
        }
    }""", 'guaranteed pump exit cleanup with preserved interruption')
    pump.write_text(text)
    factory = shared/'managed/FullScreenWindowFactory.java'
    text = replace_once(factory.read_text(), '        this(new DefaultScreenSelector(screen), s);',
                        '        this(new DefaultScreenSelector(screen), s);\n        screenMap.put(screen, new ScreenManagedWindowContainer(screen));', 'early managed root')
    text = replace_once(text, """            PlatformScreen source = (PlatformScreen) d.getSource();
            d.setSource(screenMap.get(source));""", """            Object source = d.getSource();
            if (!(source instanceof ManagedWindowContainer)) {
                d.setSource(screenMap.get((PlatformScreen) source));
            }""", 'managed root through existing full screen pump')
    factory.write_text(text)
    full_pump = shared/'managed/FullScreenEventPump.java'
    text = replace_once(full_pump.read_text(), '            c.dispatchEvent(d);', """            if (d.getId() == java.awt.event.PaintEvent.PAINT
                    || d.getId() == java.awt.event.PaintEvent.UPDATE) {
                java.awt.Rectangle area = d.getUpdateRect();
                if (area != null) c.repaint(area.x, area.y, area.width, area.height);
            } else {
                c.dispatchEvent(d);
            }""", 'recoverable screen paint invalidation')
    full_pump.write_text(text)
    robot = tta/'CTCRobotPeer.java'
    text = replace_once(robot.read_text(), '        ev.setButton(currentButton);', """        ev.setButton((currentButton & InputEvent.BUTTON1_DOWN_MASK) != 0 ? MouseEvent.BUTTON1
                : (currentButton & InputEvent.BUTTON2_DOWN_MASK) != 0 ? MouseEvent.BUTTON2
                : (currentButton & InputEvent.BUTTON3_DOWN_MASK) != 0 ? MouseEvent.BUTTON3
                : MouseEvent.NOBUTTON);""", 'Robot explicit button compatibility')
    robot.write_text(text)
    data = shared/'managed/EventData.java' 
    text = replace_once(data.read_text(), '    private int button;',
                        '    private int button;\n    private int wheelRotation;\n\n    public void setWheelRotation(int rotation) { wheelRotation = rotation; }\n    public int getWheelRotation() { return wheelRotation; }', 'dedicated wheel rotation')
    text = replace_once(text, '                                  getButton(modifierChange));',
                        '                                  (id == MouseEvent.MOUSE_PRESSED\n                                   || id == MouseEvent.MOUSE_RELEASED\n                                   || id == MouseEvent.MOUSE_CLICKED) ? button : MouseEvent.NOBUTTON);', 'explicit mouse button')
    text = replace_once(text, '                    3, button==4 ?  -1 : 1);',
                        '                    3, wheelRotation);', 'signed wheel magnitude')
    text = replace_once(text, '        button = 0;', '        button = 0;\n        wheelRotation = 0;', 'clear wheel rotation')
    data.write_text(text)

def build(source, output, jdk):
    source = checked_path(source, 'source'); output = checked_path(output, 'output'); jdk = pathlib.Path(jdk).resolve()
    project = pathlib.Path(os.path.abspath(PROJECT))
    if output == project or output in project.parents:
        raise ValueError('output may not replace the project root or any parent of it')
    if source == output or source in output.parents or output in source.parents:
        raise ValueError('source and output paths must be disjoint')
    if not source.is_dir(): raise ValueError('prepared source directory required')
    archive = source/'caciocavallo.tar.gz'
    if not archive.is_file() or hashlib.sha256(archive.read_bytes()).hexdigest() != SPEC['sha256']:
        raise ValueError('prepared source archive is missing or has the wrong SHA256')
    output.parent.mkdir(parents=True, exist_ok=True)
    # Recheck after creating the parent; no component may have become a symlink.
    output = checked_path(output, 'output')
    stage = pathlib.Path(tempfile.mkdtemp(prefix='.'+output.name+'.staged-', dir=output.parent))
    try:
        with tempfile.TemporaryDirectory(prefix='cacio-build-') as scratch_name:
            scratch = pathlib.Path(scratch_name)
            unpack = scratch/'unpack'; unpack.mkdir()
            safe_extract(archive, unpack)
            root = unpack/SPEC['source_root']
            if not root.is_dir(): raise ValueError(f'missing expected source root {root}')
            if any(p.is_symlink() for p in root.rglob('*')): raise ValueError('symlink in prepared source rejected')
            work = scratch/'patched-source'
            patched_copy(root, work)
            jars = stage/'lib'; jars.mkdir()
            inventories = {}
            for module in ('cacio-shared','cacio-tta'):
                base = work/module/'src/main/java'
                files = sorted(p for p in base.rglob('*.java') if '/ctc/junit/' not in p.as_posix())
                if any(p.is_symlink() for p in files): raise ValueError('symlink source rejected')
                inventories[module] = [p.relative_to(work).as_posix() for p in files]
                for p in files:
                    for imported in re.findall(r'^\s*import\s+(?:static\s+)?([\w.]+)', p.read_text(), re.M):
                        if not imported.startswith(('java.', 'javax.', 'sun.', 'com.github.caciocavallosilano.cacio.')):
                            raise ValueError(f'unreviewed external runtime import {imported} in {p.relative_to(work)}')
                classes = scratch/(module+'-classes'); classes.mkdir()
                argfile = scratch/(module+'.args')
                argfile.write_text('\n'.join('"'+str(p)+'"' for p in files)+'\n')
                cmd = [str(jdk/'bin/javac'), '-nowarn', *sum((['--add-exports',x] for x in EXPORTS),[]), *(['-classpath',str(scratch/'cacio-shared-classes')] if module == 'cacio-tta' else []), '-d',str(classes),'@'+str(argfile)]
                subprocess.run(cmd, check=True)
                jarname = 'cacio-shared.jar' if module == 'cacio-shared' else 'cacio-tta.jar'
                subprocess.run([str(jdk/'bin/jar'),'--create','--file',str(jars/jarname),'-C',str(classes),'.'],check=True)
            adapter_sources = sorted((PROJECT/'src-desktop').rglob('*.java'))
            if not adapter_sources: raise ValueError('desktop adapter sources are missing')
            adapter_classes = scratch/'adapter-classes'; adapter_classes.mkdir()
            adapter_args = scratch/'adapter.args'
            adapter_args.write_text('\n'.join('"'+str(p)+'"' for p in adapter_sources)+'\n')
            adapter_cp = os.pathsep.join([str(jars/'cacio-shared.jar'), str(jars/'cacio-tta.jar')])
            adapter_cmd = [str(jdk/'bin/javac'), '-nowarn', *sum((['--add-exports',x] for x in EXPORTS),[]), '-classpath',adapter_cp,'-d',str(adapter_classes),'@'+str(adapter_args)]
            subprocess.run(adapter_cmd, check=True)
            subprocess.run([str(jdk/'bin/jar'),'--create','--file',str(jars/'orbis-desktop-adapter.jar'),'-C',str(adapter_classes),'.'],check=True)
            shutil.copy2(root/'LICENSE',stage/'LICENSE')
            launch_options = expected_launch_options()
            (stage/'launch-options.txt').write_text('\n'.join(launch_options)+'\n')
            jdeps = subprocess.check_output([str(jdk/'bin/jdeps'),'--multi-release','17','--print-module-deps',str(jars/'cacio-shared.jar'),str(jars/'cacio-tta.jar'),str(jars/'orbis-desktop-adapter.jar')],text=True,stderr=subprocess.STDOUT).strip()
            manifest = {'schema':1,'repository':SPEC['repository'],'commit':SPEC['commit'],'archive_sha256':SPEC['sha256'],'license':SPEC['license'],'modules':['cacio-shared','cacio-tta','orbis-desktop-adapter'],'excluded_source_globs':['*/ctc/junit/*'],'runtime_dependencies':[],'jdk':subprocess.check_output([str(jdk/'bin/java'),'-version'],stderr=subprocess.STDOUT,text=True).splitlines()[0], 'exports':EXPORTS,'opens':OPENS,'jdeps_modules':jdeps,'sources':inventories,'bridge_abi':1,'packet_word_count':24,'jar_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(jars.glob('*.jar'))},'patches':['bounded optional desktop input delegate and finite interrupted pump exit','managed event root and explicit button/signed wheel conversion','remove CTCScreen Android LD_LIBRARY_PATH/libpojavexec initializer','add synchronized public CTCScreen initialization and fixed geometry validation','serialize all screen Graphics operations and copy canonical caller-owned ARGB snapshots with one shared close/sequence lock','return CTCScreen ARGB model from CTCToolkit','replace Android clipboard with process-local Clipboard','preserve the platform font manager rather than overriding it','exclude CTCPreloadClassLoader and FontManagerUtil','make DesktopPeer unsupported external actions fail and do not advertise discarded handlers']}
            (stage/'build-manifest.json').write_text(json.dumps(manifest,indent=2,sort_keys=True)+'\n')
            audit_bundle(stage, jdk)
        backup = None
        if output.exists():
            backup = output.parent/('.'+output.name+'.backup-'+uuid.uuid4().hex)
            output.rename(backup)
        try:
            stage.rename(output)
        except Exception:
            if backup is not None and backup.exists(): backup.rename(output)
            raise
        if backup is not None:
            if backup.is_dir(): shutil.rmtree(backup)
            else: backup.unlink()
    except Exception:
        if stage.exists(): shutil.rmtree(stage)
        raise

def main():
    p=argparse.ArgumentParser(); p.add_argument('--prepare-source',action='store_true'); p.add_argument('--source'); p.add_argument('--output'); p.add_argument('--jdk')
    a=p.parse_args()
    if a.prepare_source: prepare(); return
    if not (a.source and a.output and a.jdk): p.error('provide --source PATH --output PATH --jdk PATH, or --prepare-source')
    build(a.source,a.output,a.jdk)
if __name__=='__main__': main()
