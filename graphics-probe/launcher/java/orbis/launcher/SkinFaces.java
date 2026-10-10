package orbis.launcher;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * A player's face for the account widget: the skin's face with its hat layer, as the public avatar services
 * render it (mc-heads.net, else Crafatar). The face is kept in the launcher's cache, so it shows offline too.
 */
final class SkinFaces {
    static final int SIZE = 8;
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{32}");
    /** Public avatar services, tried in order; %s is the account's UUID without dashes. */
    static final List<String> SERVICES = List.of("https://mc-heads.net/avatar/%s/8",
            "https://crafatar.com/avatars/%s?size=8&overlay");

    /** The network seam, replaced in tests. */
    interface Source { byte[] fetch(String url, long max) throws IOException; }

    private final Path cache;
    private final Source source;

    SkinFaces(Path cache, Source source) {
        this.cache = cache;
        this.source = source;
    }

    /** The face as 8x8 ARGB pixels, from the cache or an avatar service; null when there is none to show. */
    int[] face(String uuid) throws IOException {
        String id = uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        if (!UUID.matcher(id).matches()) return null;
        Path cached = cache.resolve(id + ".png");
        if (Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            BufferedImage image = ImageIO.read(cached.toFile());
            if (image != null && image.getWidth() == SIZE && image.getHeight() == SIZE)
                return image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        }
        IOException last = null;
        for (String service : SERVICES) {
            try {
                int[] face = face(ImageIO.read(new ByteArrayInputStream(source.fetch(String.format(service, id), 1 << 16))));
                BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                out.setRGB(0, 0, SIZE, SIZE, face, 0, SIZE);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(out, "png", png);
                Files.createDirectories(cache);
                LauncherFiles.write(cached, png.toByteArray(), false);
                return face;
            } catch (IOException unavailable) {
                last = unavailable;
            }
        }
        throw last;
    }

    /** A square avatar as 8x8 opaque pixels; a larger one is reduced with nearest sampling, keeping its pixels. */
    static int[] face(BufferedImage avatar) throws IOException {
        if (avatar == null || avatar.getWidth() != avatar.getHeight() || avatar.getWidth() < SIZE)
            throw new IOException("Not a face image");
        int cell = avatar.getWidth() / SIZE;
        int[] pixels = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++)
                pixels[y * SIZE + x] = avatar.getRGB(x * cell + cell / 2, y * cell + cell / 2) | 0xff000000;
        return pixels;
    }
}
