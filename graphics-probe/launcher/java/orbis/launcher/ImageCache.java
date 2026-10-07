package orbis.launcher;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Pictures from the web (Modrinth icons, banners, gallery images) and from mod JARs, as textures.
 * Two background threads download (through a disk cache of the original bytes), decode (WebP with
 * the vendored TwelveMonkeys reader, everything else with ImageIO) and shrink them to the size they
 * are shown at; the GL thread uploads a few per frame. Textures not drawn for a while are dropped
 * once the total passes a budget, and come back on demand.
 */
class ImageCache {
    /** The largest size a picture is kept at; it is scaled down to fit, never up. */
    enum Size {
        ICON(128, 128), THUMB(480, 300), BANNER(1680, 640), FULL(1920, 1080);
        final int width, height;
        Size(int width, int height) { this.width = width; this.height = height; }
    }

    /** An uploaded picture: its texture and pixel size (for keeping the aspect ratio). */
    record Picture(int texture, int width, int height) {}

    /** Where bytes come from; tests answer without the network. */
    interface Source { byte[] fetch(String url) throws IOException; }

    private static final long TEXTURE_BUDGET = 96L << 20;
    private static final long DISK_BUDGET = 192L << 20;
    private static final int UPLOADS_PER_FRAME = 3;
    private static final int MAX_DIMENSION = 8192;

    private enum State { LOADING, DECODED, READY, FAILED }

    private static final class Entry {
        volatile State state = State.LOADING;
        volatile ByteBuffer pixels;
        volatile int width, height;
        int texture;
        long lastUsed;
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private final ConcurrentLinkedQueue<Entry> decoded = new ConcurrentLinkedQueue<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        Thread t = new Thread(task, "launcher images");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Source source;
    private final Path disk;
    private long frame, textureBytes;

    /** disk: a folder for downloaded bytes, or null for none. */
    ImageCache(Source source, Path disk) {
        this.source = source;
        this.disk = disk;
        if (disk != null) workers.execute(this::pruneDisk);
    }

    /** The picture once it is ready; null while it loads or when it cannot be shown. Starts loading on first use. */
    synchronized Picture get(String url, Size size) {
        if (url == null || url.isEmpty()) return null;
        String key = size.name() + ' ' + url;
        Entry e = entries.get(key);
        if (e == null) {
            Entry created = new Entry();
            entries.put(key, created);
            workers.execute(() -> load(created, url, size, () -> fetch(url)));
            e = created;
        }
        e.lastUsed = frame;
        return e.state == State.READY ? new Picture(e.texture, e.width, e.height) : null;
    }

    /** Like {@link #get}, for bytes already at hand (a mod JAR's icon); key names them. */
    synchronized Picture get(String key, byte[] bytes, Size size) {
        if (bytes == null) return null;
        String full = size.name() + " local:" + key;
        Entry e = entries.get(full);
        if (e == null) {
            Entry created = new Entry();
            entries.put(full, created);
            workers.execute(() -> load(created, key, size, () -> bytes));
            e = created;
        }
        e.lastUsed = frame;
        return e.state == State.READY ? new Picture(e.texture, e.width, e.height) : null;
    }

    /** Whether the picture is still on its way (draw a placeholder) rather than failed. */
    synchronized boolean loading(String url, Size size) {
        Entry e = entries.get(size.name() + ' ' + url);
        return e != null && (e.state == State.LOADING || e.state == State.DECODED);
    }

    /** Once per frame on the GL thread: upload what was decoded, drop what has not been drawn. */
    synchronized void update(Renderer r) {
        frame++;
        for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
            Entry e = decoded.poll();
            if (e == null) break;
            if (e.state != State.DECODED) continue;
            e.texture = r.upload(e.width, e.height, e.pixels);
            textureBytes += 4L * e.width * e.height;
            e.pixels = null;
            e.state = State.READY;
        }
        if (textureBytes <= TEXTURE_BUDGET) return;
        // Least recently used first (access order); never what was drawn in the last two frames.
        for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
             it.hasNext() && textureBytes > TEXTURE_BUDGET * 3 / 4; ) {
            Entry e = it.next().getValue();
            if (e.state != State.READY || frame - e.lastUsed < 2) continue;
            r.deleteTexture(e.texture);
            textureBytes -= 4L * e.width * e.height;
            it.remove();
        }
    }

    // ------------------------------------------------------------------ background

    private interface Bytes { byte[] get() throws IOException; }

    private void load(Entry e, String name, Size size, Bytes bytes) {
        try {
            BufferedImage image = decode(bytes.get(), size);
            BufferedImage fitted = fit(image, size);
            e.width = fitted.getWidth();
            e.height = fitted.getHeight();
            e.pixels = rgba(fitted);
            e.state = State.DECODED;
            decoded.add(e);
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            e.state = State.FAILED;
            System.err.println("launcher: image " + name + " unavailable: " + failure);
        }
    }

    private byte[] fetch(String url) throws IOException {
        if (disk == null) return source.fetch(url);
        Path file = disk.resolve(ModrinthClient.sha1(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (Files.isRegularFile(file)) {
            try {
                Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
                return Files.readAllBytes(file);
            } catch (IOException unreadable) {
                // fetch it again
            }
        }
        byte[] data = source.fetch(url);
        try {
            Files.createDirectories(disk);
            Path part = Files.createTempFile(disk, ".part-", "");
            Files.write(part, data);
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException full) {
            // still shown, just not kept
        }
        return data;
    }

    /** Oldest files go first once the folder is over its budget. */
    private void pruneDisk() {
        List<Path> files = new ArrayList<>();
        long total = 0;
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(disk)) {
            for (Path p : dir) {
                if (p.getFileName().toString().startsWith(".part-")) { Files.deleteIfExists(p); continue; }
                files.add(p);
                total += Files.size(p);
            }
            files.sort((a, b) -> {
                try { return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b)); }
                catch (IOException gone) { return 0; }
            });
            for (Path p : files) {
                if (total <= DISK_BUDGET) break;
                long size = Files.size(p);
                Files.deleteIfExists(p);
                total -= size;
            }
        } catch (IOException missing) {
            // nothing cached yet
        }
    }

    /** WebP (RIFF....WEBP) through TwelveMonkeys, the rest through ImageIO; large images are subsampled while read. */
    static BufferedImage decode(byte[] data, Size size) throws IOException {
        boolean webp = data.length > 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
        ImageReader reader;
        if (webp) {
            reader = new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi().createReaderInstance();
        } else {
            try (ImageInputStream probe = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                Iterator<ImageReader> found = ImageIO.getImageReaders(probe);
                if (!found.hasNext()) throw new IOException("unknown image format");
                reader = found.next();
            }
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(in, true, true);
            int w = reader.getWidth(0), h = reader.getHeight(0);
            if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION)
                throw new IOException("image size " + w + "x" + h);
            ImageReadParam param = reader.getDefaultReadParam();
            int step = Math.max(1, Math.min(w / size.width, h / size.height) / 2);
            if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
            BufferedImage image = reader.read(0, param);
            if (image == null) throw new IOException("empty image");
            return image;
        } finally {
            reader.dispose();
        }
    }

    /** Scaled down to fit the size, keeping its aspect ratio; already-small images are kept as they are. */
    static BufferedImage fit(BufferedImage image, Size size) {
        int w = image.getWidth(), h = image.getHeight();
        double scale = Math.min(1.0, Math.min((double) size.width / w, (double) size.height / h));
        int tw = Math.max(1, (int) Math.round(w * scale)), th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static ByteBuffer rgba(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer out = ByteBuffer.allocateDirect(argb.length * 4);
        for (int p : argb) out.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        out.flip();
        return out;
    }
}
