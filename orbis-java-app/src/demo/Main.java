package demo;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.rmi.server.UID;
import javax.script.ScriptEngineManager;
import java.text.DateFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.time.Instant;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Method;
import javax.naming.CompositeName;
import javax.security.sasl.Sasl;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.sql.rowset.RowSetProvider;
import org.ietf.jgss.Oid;
import com.sun.net.httpserver.HttpServer;
import com.sun.security.auth.UserPrincipal;
import com.sun.security.jgss.ExtendedGSSContext;
import java.net.InetSocketAddress;
import java.nio.file.attribute.FileAttribute;
import javax.lang.model.SourceVersion;
import javax.tools.SimpleJavaFileObject;
import jdk.dynalink.StandardOperation;
import jdk.net.ExtendedSocketOptions;
import jdk.nio.mapmode.ExtendedMapMode;
import org.w3c.dom.css.CSSPrimitiveValue;

public final class Main {
    private static final Path ROOT = Path.of(
            System.getProperty("orbis.probe.root", "/data/orbis-jdk"));
    private static final Path APP_JAR = Path.of(
            System.getProperty("orbis.app.jar", "/app0/app.jar"));
    private static final Path LOG = ROOT.resolve("logs/orbis-java.log");
    private static final Path ROTATED = ROOT.resolve("logs/orbis-java.log.1");
    private static final long LOG_LIMIT = 256 * 1024;

    private Main() {}

    @FunctionalInterface
    private interface Check { void run() throws Throwable; }

    private static final class Log implements AutoCloseable {
        private final AtomicInteger pass = new AtomicInteger();
        private final AtomicInteger skip = new AtomicInteger();
        private final AtomicInteger fail = new AtomicInteger();

        static Log open() {
            try {
                Files.createDirectories(LOG.getParent());
                if (Files.exists(LOG) && Files.size(LOG) >= LOG_LIMIT) {
                    try { Files.move(LOG, ROTATED,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                    catch (Throwable ignored) { Files.deleteIfExists(LOG); }
                }
            } catch (Throwable error) {
                System.err.println("orbis-java: file logger unavailable: " + error);
            }
            return new Log();
        }

        synchronized void line(String level, String component, String message) {
            String record = Instant.now() + " " + level + " " + component
                    + " " + message + "\n";
            try {
                byte[] bytes = record.getBytes(StandardCharsets.UTF_8);
                long size = Files.exists(LOG) ? Files.size(LOG) : 0;
                if (size + bytes.length > LOG_LIMIT) {
                    try { Files.move(LOG, ROTATED,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                    catch (Throwable ignored) { Files.deleteIfExists(LOG); }
                }
                Files.write(LOG, bytes, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (Throwable error) {
                if (!"logger".equals(component))
                    System.err.println("orbis-java: probe log write failed: " + error);
            }
        }

        void result(String name, String status, String detail) {
            if ("PASS".equals(status)) pass.incrementAndGet();
            else if ("SKIP".equals(status)) skip.incrementAndGet();
            else fail.incrementAndGet();
            line("INFO", name, status + " " + detail);
        }

        void check(String name, Check check) {
            line("INFO", name, "BEGIN");
            try {
                check.run();
                result(name, "PASS", "ok");
            } catch (Throwable error) {
                String status = name.equals("probe.network") && isUnsupported(error)
                        ? "SKIP" : "FAIL";
                result(name, status, error.toString());
            }
        }

        private static boolean isUnsupported(Throwable error) {
            return error instanceof UnsupportedOperationException
                    || error instanceof UnsatisfiedLinkError
                    || error instanceof NoClassDefFoundError
                    || error instanceof java.net.SocketException;
        }

        void summary() {
            line("INFO", "probe.summary", "PASS=" + pass + " SKIP=" + skip
                    + " FAIL=" + fail);
        }

        @Override public void close() { line("INFO", "probe", "logger closed"); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Orbis Java 17 runtime probe");
        boolean probeFailed = false;
        try (Log log = Log.open()) {
            log.line("INFO", "probe", "begin");
            log.check("probe.args", () -> argsProbe(args));
            log.check("probe.vm", () -> {
                require(System.getProperty("java.version"));
                require(System.getProperty("java.home"));
                require(System.getProperty("java.class.path"));
            });
            log.check("probe.class", () -> {
                require(Class.forName("demo.Main"));
                require(Class.forName("java.lang.String"));
            });
            log.check("probe.resource", () -> {
                try (InputStream input = Main.class.getResourceAsStream("/demo/Main.class")) {
                    if (input == null || input.read() != 0xca || input.read() != 0xfe)
                        throw new IOException("application resource unavailable");
                }
            });
            log.check("probe.reflection", Main::reflectionProbe);
            log.check("probe.file", Main::fileProbe);
            log.check("probe.directory", Main::directoryProbe);
            log.check("probe.thread", Main::threadProbe);
            log.check("probe.concurrent", Main::concurrentProbe);
            log.check("probe.timing", () -> timingProbe(log));
            log.check("probe.jar", Main::jarProbe);
            log.check("probe.memory", Main::memoryProbe);
            log.check("probe.filesystemStress", Main::filesystemStressProbe);
            log.check("probe.digest", Main::digestProbe);
            log.check("probe.endurance", Main::enduranceProbe);
            log.check("probe.logging", Main::loggingProbe);
            log.check("probe.selector", Main::selectorProbe);
            log.check("probe.nio", Main::nioProbe);
            log.check("probe.xml", Main::xmlProbe);
            log.check("probe.http", Main::httpProbe);
            log.check("probe.sql", Main::sqlProbe);
            log.check("probe.xa", Main::xaProbe);
            log.check("probe.network", Main::networkProbe);
            log.check("probe.networkStress", Main::networkStressProbe);
            log.check("probe.platform", Main::platformProbe);
            log.check("probe.naming", Main::namingProbe);
            log.check("probe.gss", Main::gssProbe);
            log.check("probe.sasl", Main::saslProbe);
            log.check("probe.xmlcrypto", Main::xmlCryptoProbe);
            log.check("probe.rowset", Main::rowSetProbe);
            log.check("probe.scripting", Main::scriptingProbe);
            log.check("probe.rmi", Main::rmiProbe);
            log.check("probe.charsets", Main::charsetsProbe);
            log.check("probe.localedata", Main::localeDataProbe);
            log.check("probe.random", Main::randomProbe);
            log.check("probe.unsupported", Main::unsupportedProbe);
            log.check("probe.zipfs", () -> zipFsProbe(log));
            log.check("probe.compilerApi", Main::compilerApiProbe);
            log.check("probe.httpserver", Main::httpServerProbe);
            log.check("probe.namingDns", () -> providerProbe(
                    "com.sun.jndi.dns.DnsContextFactory"));
            log.check("probe.namingRmi", () -> providerProbe(
                    "com.sun.jndi.rmi.registry.RegistryContextFactory"));
            log.check("probe.extendedNetwork", Main::extendedNetworkProbe);
            log.check("probe.mapmode", Main::mapModeProbe);
            log.check("probe.securityAuth", Main::securityAuthProbe);
            log.check("probe.extendedGss", Main::extendedGssProbe);
            log.check("probe.xmlDom", Main::xmlDomProbe);
            log.check("probe.dynalink", Main::dynalinkProbe);
            log.summary();
            probeFailed = log.fail.get() != 0;
        }
        if (probeFailed)
            throw new IllegalStateException("probe failures recorded");
    }

    private static void argsProbe(String[] args) {
        if (args == null) throw new IllegalStateException("argument array missing");
        for (String arg : args) require(arg);
    }

    private static void reflectionProbe() throws Exception {
        Method method = Main.class.getDeclaredMethod("reflectionValue", String.class);
        Object result = method.invoke(null, "orbis");
        if (!"orbis-reflected".equals(result))
            throw new IllegalStateException("reflection result mismatch");
    }

    private static String reflectionValue(String value) {
        return value + "-reflected";
    }

    private static void fileProbe() throws IOException {
        Path directory = ROOT.resolve("probe");
        Path file = directory.resolve("roundtrip.bin");
        Files.createDirectories(directory);
        byte[] expected = "orbis-java-probe\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, expected, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        ByteBuffer buffer = ByteBuffer.allocate(expected.length);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            if (channel.size() != expected.length)
                throw new IOException("file channel size mismatch");
            ByteBuffer positioned = ByteBuffer.allocate(expected.length);
            if (channel.read(positioned, 0) != expected.length
                    || !java.util.Arrays.equals(expected, positioned.array()))
                throw new IOException("positioned file read mismatch");
            channel.position(0);
            while (buffer.hasRemaining()) channel.read(buffer);
        }
        if (!java.util.Arrays.equals(expected, buffer.array()))
            throw new IOException("file roundtrip mismatch");
    }

    private static void directoryProbe() throws IOException {
        Files.createDirectories(ROOT.resolve("probe-dir"));
        try (var entries = Files.list(ROOT)) {
            if (!entries.findAny().isPresent())
                throw new IOException("directory enumeration empty");
        }
    }

    private static void threadProbe() throws InterruptedException {
        AtomicInteger value = new AtomicInteger();
        Thread thread = new Thread(value::incrementAndGet, "orbis-java-probe");
        thread.start();
        thread.join(5000);
        if (thread.isAlive() || value.get() != 1)
            throw new IllegalStateException("thread did not join");
    }

    private static void concurrentProbe() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> 2);
            Future<Integer> second = executor.submit(() -> 3);
            if (first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS) != 5)
                throw new IllegalStateException("executor result mismatch");
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS))
                executor.shutdownNow();
        }
    }

    private static void timingProbe(Log log) throws Exception {
        AtomicInteger completed = new AtomicInteger();
        log.line("INFO", "probe.timing", "timing.construct-worker");
        Thread sleeper = new Thread(() -> {
            log.line("INFO", "probe.timing", "timing.worker-entered");
            try {
                log.line("INFO", "probe.timing", "timing.worker-before-sleep");
                Thread.sleep(120);
                log.line("INFO", "probe.timing", "timing.worker-after-sleep");
            } catch (InterruptedException error) {
                log.line("INFO", "probe.timing", "timing.worker-interrupted");
                Thread.currentThread().interrupt();
            } finally {
                completed.incrementAndGet();
            }
        }, "orbis-java-timing-probe");
        long start = System.nanoTime();
        log.line("INFO", "probe.timing", "timing.before-start");
        sleeper.start();
        log.line("INFO", "probe.timing", "timing.after-start");
        log.line("INFO", "probe.timing", "timing.before-join");
        sleeper.join(5000);
        log.line("INFO", "probe.timing", "timing.after-join alive=" + sleeper.isAlive());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (sleeper.isAlive() || completed.get() != 1 || elapsed < 75 || elapsed > 5000)
            throw new IllegalStateException("timed wait elapsed=" + elapsed);

        AtomicInteger release = new AtomicInteger();
        Thread parked = new Thread(() -> {
            while (release.get() == 0) Thread.yield();
        }, "orbis-java-timed-join-probe");
        parked.start();
        long joinStart = System.nanoTime();
        long joinWallStart = System.currentTimeMillis();
        log.line("INFO", "probe.timing", "timedJoin.before-bounded-join");
        parked.join(120);
        long joinElapsed = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - joinStart);
        long joinWallElapsed = System.currentTimeMillis() - joinWallStart;
        log.line("INFO", "probe.timing", "timedJoin.after-bounded-join alive="
                + parked.isAlive() + " elapsed=" + joinElapsed
                + " wallElapsed=" + joinWallElapsed);
        if (!parked.isAlive() || joinElapsed < 75 || joinElapsed > 5000
                || joinWallElapsed < 75 || joinWallElapsed > 5000
                || joinElapsed * 3 < joinWallElapsed
                || joinWallElapsed * 3 < joinElapsed)
            throw new IllegalStateException("timed join elapsed=" + joinElapsed
                    + " wallElapsed=" + joinWallElapsed);
        release.set(1);
        parked.join(5000);
        if (parked.isAlive())
            throw new IllegalStateException("timed join worker did not stop");
    }

    private static void filesystemStressProbe() throws IOException {
        Path directory = ROOT.resolve("probe/fs-stress");
        Path original = directory.resolve("state.bin");
        Path renamed = directory.resolve("state-renamed.bin");
        Files.createDirectories(directory);
        Files.deleteIfExists(original);
        Files.deleteIfExists(renamed);
        byte[] first = new byte[4096];
        for (int i = 0; i < first.length; ++i) first[i] = (byte) (i * 13);
        Files.write(original, first, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        if (Files.size(original) != first.length)
            throw new IOException("initial size mismatch");
        try (FileChannel channel = FileChannel.open(original,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.position(257);
            ByteBuffer one = ByteBuffer.allocate(1);
            if (channel.read(one) != 1 || one.array()[0] != first[257])
                throw new IOException("offset read mismatch");
            channel.truncate(8192);
            if (channel.size() != first.length)
                throw new IOException("truncate unexpectedly grew file");
            channel.truncate(1024);
            if (channel.size() != 1024)
                throw new IOException("truncate shrink mismatch");
        }
        Files.move(original, renamed, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        if (Files.exists(original) || !Files.exists(renamed)
                || Files.size(renamed) != 1024)
            throw new IOException("rename state mismatch");
        if (!Files.deleteIfExists(renamed) || Files.deleteIfExists(renamed))
            throw new IOException("delete state mismatch");
    }

    private static void jarProbe() throws IOException {
        try (JarFile jar = new JarFile(APP_JAR.toString())) {
            JarEntry entry = jar.getJarEntry("demo/Main.class");
            if (entry == null) throw new IOException("application JAR entry missing");
            try (InputStream input = jar.getInputStream(entry)) {
                if (input.read() != 0xca || input.read() != 0xfe)
                    throw new IOException("application JAR entry invalid");
            }
        }
    }

    private static void memoryProbe() {
        byte[] data = new byte[1024 * 1024];
        long checksum = 0;
        for (int i = 0; i < data.length; ++i) {
            data[i] = (byte) (i * 31);
            checksum += data[i] & 0xff;
        }
        if (checksum == 0) throw new IllegalStateException("allocation checksum empty");
    }

    private static void digestProbe() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String actual = HexFormat.of().formatHex(digest.digest(
                "orbis-java".getBytes(StandardCharsets.UTF_8)));
        if (!"0356fdcfb75bf63675ab3442259dfe0f846ddb135998d059eaee84df54d2f577".equals(actual))
            throw new IllegalStateException("digest mismatch");
    }

    private static void loggingProbe() {
        Logger logger = Logger.getLogger("orbis-java-probe-" + System.nanoTime());
        AtomicInteger records = new AtomicInteger();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record != null) records.incrementAndGet();
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.INFO);
        logger.log(Level.INFO, "orbis logging probe");
        logger.removeHandler(handler);
        handler.close();
        if (records.get() != 1)
            throw new IllegalStateException("logging record was not delivered");
    }

    private static void xmlProbe() throws Exception {
        byte[] xml = "<root><value>orbis</value></root>".getBytes(StandardCharsets.UTF_8);
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml));
        String value = document.getDocumentElement().getElementsByTagName("value")
                .item(0).getTextContent();
        if (!"orbis".equals(value))
            throw new IllegalStateException("XML value mismatch");
    }

    private static void selectorProbe() throws IOException {
        try (Selector selector = Selector.open()) {
            if (!selector.isOpen())
                throw new IOException("selector did not open");
        }
    }

    private static void nioProbe() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1)) {
            Thread acceptor = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.getInputStream().read();
                } catch (IOException ignored) {}
            }, "orbis-java-probe-nio");
            acceptor.start();
            boolean connected;
            try (SocketChannel channel = SocketChannel.open()) {
                channel.configureBlocking(false);
                channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                connected = channel.connect(
                        new java.net.InetSocketAddress("127.0.0.1", server.getLocalPort()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!connected && System.nanoTime() < deadline) {
                    connected = channel.finishConnect();
                    if (!connected) Thread.sleep(1);
                }
                if (!connected)
                    throw new IOException("nonblocking connect did not finish");
                channel.write(ByteBuffer.wrap(new byte[] { 0x4f }));
            }
            acceptor.join(5000);
            if (acceptor.isAlive())
                throw new IOException("nonblocking acceptor did not join");
        }
    }

    private static void httpProbe() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1)) {
            AtomicInteger serverError = new AtomicInteger();
            Thread acceptor = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    if (socket.getInputStream().read() < 0)
                        throw new IOException("HTTP request was empty");
                    byte[] response = ("HTTP/1.1 200 OK\r\n"
                            + "Content-Length: 10\r\n"
                            + "Connection: close\r\n\r\n"
                            + "orbis-http").getBytes(StandardCharsets.US_ASCII);
                    socket.getOutputStream().write(response);
                    socket.getOutputStream().flush();
                } catch (Throwable error) {
                    serverError.set(1);
                }
            }, "orbis-java-probe-http");
            acceptor.start();
            HttpClient client = HttpClient.newBuilder().build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/probe"))
                    .GET().build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            acceptor.join(5000);
            if (serverError.get() != 0 || acceptor.isAlive())
                throw new IOException("HTTP acceptor failed");
            if (response.statusCode() != 200 || !"orbis-http".equals(response.body()))
                throw new IOException("HTTP response mismatch");
        }
    }

    private static void sqlProbe() {
        if (Types.INTEGER != 4 || DriverManager.getDrivers() == null)
            throw new IllegalStateException("JDBC API unavailable");
    }

    private static void xaProbe() {
        XAException error = new XAException(XAException.XAER_RMERR);
        if (error.errorCode != XAException.XAER_RMERR)
            throw new IllegalStateException("XA error code mismatch");
        Xid xid = new Xid() {
            @Override public int getFormatId() { return 0x4f524249; }
            @Override public byte[] getGlobalTransactionId() { return new byte[] { 1, 2, 3 }; }
            @Override public byte[] getBranchQualifier() { return new byte[] { 4, 5, 6 }; }
        };
        if (xid.getFormatId() != 0x4f524249 || xid.getGlobalTransactionId().length != 3
                || xid.getBranchQualifier().length != 3)
            throw new IllegalStateException("Xid contract mismatch");
    }

    private static void namingProbe() throws Exception {
        CompositeName name = new CompositeName("orbis/java");
        if (name.size() != 2 || !"java".equals(name.get(1)))
            throw new IllegalStateException("JNDI composite name mismatch");
    }

    private static void gssProbe() throws Exception {
        Oid oid = new Oid("1.2.840.113554.1.2.2");
        if (!"1.2.840.113554.1.2.2".equals(oid.toString()))
            throw new IllegalStateException("GSS OID mismatch");
    }

    private static void saslProbe() throws Exception {
        if (Sasl.getSaslClientFactories() == null)
            throw new IllegalStateException("SASL client factory enumeration unavailable");
        if (Sasl.createSaslClient(new String[] { "ORBiS-UNKNOWN" }, null,
                "service", "localhost", java.util.Map.of(), null) != null)
            throw new IllegalStateException("unexpected SASL mechanism provider");
    }

    private static void xmlCryptoProbe() {
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        if (factory == null || !"DOM".equals(factory.getMechanismType()))
            throw new IllegalStateException("XML signature provider mismatch");
    }

    private static void rowSetProbe() throws Exception {
        if (RowSetProvider.newFactory().createCachedRowSet() == null)
            throw new IllegalStateException("rowset provider returned null");
    }

    private static void scriptingProbe() throws Exception {
        ScriptEngineManager manager = new ScriptEngineManager();
        if (manager.getEngineFactories() == null
                || Class.forName("javax.script.ScriptEngine") == null)
            throw new IllegalStateException("scripting API unavailable");
    }

    private static void rmiProbe() {
        UID uid = new UID();
        if (uid.toString().isEmpty())
            throw new IllegalStateException("RMI UID was empty");
    }

    private static void charsetsProbe() {
        Charset charset = Charset.forName("windows-1252");
        if (!charset.newEncoder().canEncode("orbis"))
            throw new IllegalStateException("extended charset unavailable");
    }

    private static void localeDataProbe() {
        Locale locale = Locale.forLanguageTag("ar-LY");
        String[] months = DateFormatSymbols.getInstance(locale).getMonths();
        if (months.length < 12 || months[0] == null || months[0].isEmpty())
            throw new IllegalStateException("locale data unavailable");
    }

    private static void randomProbe() {
        RandomGeneratorFactory<RandomGenerator> factory =
                RandomGeneratorFactory.of("L64X128MixRandom");
        RandomGenerator generator = factory.create(0x4f52424953L);
        if (generator.nextLong() == Long.MIN_VALUE && generator.nextLong() == Long.MIN_VALUE)
            throw new IllegalStateException("random generator did not advance");
    }

    private static void unsupportedProbe() throws Exception {
        if (!"sun.misc.Unsafe".equals(Class.forName("sun.misc.Unsafe").getName()))
            throw new IllegalStateException("unsupported utility class unavailable");
    }

    private static void zipFsProbe(Log log) throws IOException {
        Path archive = ROOT.resolve("probe/headless.zip");
        log.line("INFO", "probe.zipfs", "before initial delete");
        boolean removedBefore = Files.deleteIfExists(archive);
        log.line("INFO", "probe.zipfs", "after initial delete existed=" + removedBefore);
        URI uri = URI.create("jar:" + archive.toUri());
        log.line("INFO", "probe.zipfs", "before newFileSystem");
        try (FileSystem zip = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            log.line("INFO", "probe.zipfs", "after newFileSystem");
            Path entry = zip.getPath("/probe.txt");
            log.line("INFO", "probe.zipfs", "before entry write");
            Files.writeString(entry, "orbis-zipfs", StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (!"orbis-zipfs".equals(Files.readString(entry)))
                throw new IOException("ZIP filesystem roundtrip mismatch");
            log.line("INFO", "probe.zipfs", "after entry roundtrip");
        }
        log.line("INFO", "probe.zipfs", "after close");
        log.line("INFO", "probe.zipfs", "before final delete");
        boolean removedAfter = Files.deleteIfExists(archive);
        log.line("INFO", "probe.zipfs", "after final delete existed=" + removedAfter);
    }

    private static void compilerApiProbe() {
        if (SourceVersion.latest() != SourceVersion.RELEASE_17)
            throw new IllegalStateException("compiler source level mismatch");
        SimpleJavaFileObject source = new SimpleJavaFileObject(
                URI.create("string:///Probe.java"),
                javax.tools.JavaFileObject.Kind.SOURCE) {};
        if (!"/Probe.java".equals(source.getName()))
            throw new IllegalStateException("compiler file object mismatch");
    }

    private static void httpServerProbe() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            if (server.getAddress().getPort() <= 0)
                throw new IOException("HTTP server did not bind");
        } finally {
            server.stop(0);
        }
    }

    private static void providerProbe(String className) throws Exception {
        if (!className.equals(Class.forName(className).getName()))
            throw new IllegalStateException("provider class mismatch");
    }

    private static void extendedNetworkProbe() {
        if (ExtendedSocketOptions.TCP_KEEPIDLE == null)
            throw new IllegalStateException("extended socket options unavailable");
    }

    private static void mapModeProbe() {
        if (ExtendedMapMode.READ_ONLY_SYNC == null || ExtendedMapMode.READ_WRITE_SYNC == null)
            throw new IllegalStateException("extended map modes unavailable");
    }

    private static void securityAuthProbe() {
        UserPrincipal principal = new UserPrincipal("orbis");
        if (!"orbis".equals(principal.getName()))
            throw new IllegalStateException("security principal mismatch");
    }

    private static void extendedGssProbe() {
        if (!ExtendedGSSContext.class.isInterface())
            throw new IllegalStateException("extended GSS API mismatch");
    }

    private static void xmlDomProbe() {
        if (CSSPrimitiveValue.CSS_PX <= 0)
            throw new IllegalStateException("DOM CSS constants unavailable");
    }

    private static void dynalinkProbe() {
        if (!"GET".equals(StandardOperation.GET.toString()))
            throw new IllegalStateException("Dynalink operation mismatch");
    }

    private static void enduranceProbe() throws Exception {
        Path file = ROOT.resolve("probe/endurance.bin");
        Files.createDirectories(file.getParent());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int rounds = 0;
        try {
            while (System.nanoTime() < deadline) {
                byte[] expected = new byte[128 * 1024];
                for (int i = 0; i < expected.length; ++i)
                    expected[i] = (byte) (i * 17 + rounds);
                Files.write(file, expected, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                if (!java.util.Arrays.equals(expected, Files.readAllBytes(file)))
                    throw new IOException("endurance file mismatch");

                int expectedResult = rounds + 7;
                Future<Integer> future = executor.submit(() -> expectedResult);
                if (future.get(5, TimeUnit.SECONDS) != expectedResult)
                    throw new IllegalStateException("endurance executor mismatch");
                Class.forName("java.util.concurrent.CompletableFuture");

                if ((rounds & 7) == 0) {
                    jarProbe();
                    networkProbe();
                }
                ++rounds;
            }
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS))
                executor.shutdownNow();
        }
        if (rounds < 5)
            throw new IllegalStateException("endurance completed too few rounds: " + rounds);
    }

    private static void networkProbe() throws IOException, InterruptedException {
        try (ServerSocket server = new ServerSocket(0, 1)) {
            Thread acceptor = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write(0x4f);
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {}
            }, "orbis-java-probe-network");
            acceptor.start();
            try (Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
                if (socket.getInputStream().read() != 0x4f)
                    throw new IOException("loopback byte mismatch");
            }
            acceptor.join(5000);
            if (acceptor.isAlive()) throw new IOException("acceptor did not join");
        }
    }

    private static void networkStressProbe() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1)) {
            for (int round = 0; round < 100; ++round) {
                AtomicInteger serverError = new AtomicInteger();
                Thread acceptor = new Thread(() -> {
                    try (Socket socket = server.accept()) {
                        if (socket.getInputStream().read() != 0x4f)
                            throw new IOException("stress request mismatch");
                        socket.getOutputStream().write(0x4b);
                        socket.getOutputStream().flush();
                    } catch (Throwable error) {
                        serverError.set(1);
                    }
                }, "orbis-java-network-stress");
                acceptor.start();
                try (Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
                    socket.getOutputStream().write(0x4f);
                    socket.getOutputStream().flush();
                    if (socket.getInputStream().read() != 0x4b)
                        throw new IOException("stress response mismatch");
                }
                acceptor.join(5000);
                if (serverError.get() != 0 || acceptor.isAlive())
                    throw new IOException("stress acceptor failed at round " + round);
            }
        }
    }

    private static void platformProbe() throws Exception {
        require(System.getProperty("java.home"));
        require(System.getProperty("java.io.tmpdir"));
        require(System.getProperty("user.dir"));
        require(System.getProperty("file.separator"));
        require(System.getProperty("path.separator"));
        if (!Charset.defaultCharset().name().equalsIgnoreCase(
                System.getProperty("file.encoding")))
            throw new IllegalStateException("default charset/property mismatch");
        if (Locale.getDefault() == null || java.util.TimeZone.getDefault() == null)
            throw new IllegalStateException("platform defaults unavailable");
        long first = System.nanoTime();
        long second = System.nanoTime();
        if (second < first)
            throw new IllegalStateException("monotonic clock moved backwards");
        require(Class.forName("java.net.http.HttpClient"));
        require(Class.forName("java.nio.file.FileSystems"));
    }

    private static void require(Object value) {
        if (value == null || (value instanceof String && ((String) value).isEmpty()))
            throw new IllegalStateException("required property missing");
    }
}
