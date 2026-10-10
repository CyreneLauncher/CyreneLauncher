package orbis.launcher;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A player's face from their skin, as Minecraft draws it: the 8x8 face with the hat layer over it. The skin
 * comes from Mojang's public profile service and the face is kept in the launcher's cache, so the account
 * widget shows it offline too.
 */
final class SkinFaces {
    static final int SIZE = 8;
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{32}");
    private static final String PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/";

    /** The network seam, replaced in tests. */
    interface Source { byte[] fetch(String url, long max) throws IOException; }

    private final Path cache;
    private final Source source;

    SkinFaces(Path cache, Source source) {
        this.cache = cache;
        this.source = source;
    }

    /** The face as 8x8 ARGB pixels, from the cache or the network; null when there is none to show. */
    int[] face(String uuid) throws IOException {
        String id = uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        if (!UUID.matcher(id).matches()) return null;
        Path cached = cache.resolve(id + ".png");
        if (Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            BufferedImage image = ImageIO.read(cached.toFile());
            if (image != null && image.getWidth() == SIZE && image.getHeight() == SIZE)
                return image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        }
        String url = skinUrl(new String(source.fetch(PROFILE + id, 65536), StandardCharsets.UTF_8));
        if (url == null) return null;
        int[] face = crop(ImageIO.read(new ByteArrayInputStream(source.fetch(url, 1 << 20))));
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, SIZE, SIZE, face, 0, SIZE);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(out, "png", png);
        Files.createDirectories(cache);
        LauncherFiles.write(cached, png.toByteArray(), false);
        return face;
    }

    /** The skin texture's address in a profile's textures property, over HTTPS. */
    static String skinUrl(String profileJson) {
        JsonObject profile = JsonParser.parseString(profileJson).getAsJsonObject();
        if (!profile.has("properties")) return null;
        for (var element : profile.getAsJsonArray("properties")) {
            JsonObject property = element.getAsJsonObject();
            if (!"textures".equals(property.get("name").getAsString())) continue;
            JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(
                    property.get("value").getAsString()), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("textures");
            if (textures == null || !textures.has("SKIN")) return null;
            String url = textures.getAsJsonObject("SKIN").get("url").getAsString();
            if (url.startsWith("http://textures.minecraft.net/")) url = "https://" + url.substring("http://".length());
            return url.startsWith("https://textures.minecraft.net/") ? url : null;
        }
        return null;
    }

    /** The face (8,8) with the hat layer (40,8) drawn over it where the hat is opaque. */
    static int[] crop(BufferedImage skin) throws IOException {
        if (skin == null || skin.getWidth() != 64 || skin.getHeight() != 64 && skin.getHeight() != 32)
            throw new IOException("Not a Minecraft skin");
        int[] face = skin.getRGB(8, 8, SIZE, SIZE, null, 0, SIZE);
        int[] hat = skin.getRGB(40, 8, SIZE, SIZE, null, 0, SIZE);
        for (int i = 0; i < face.length; i++) {
            face[i] |= 0xff000000;
            if ((hat[i] >>> 24) > 127) face[i] = hat[i] | 0xff000000;
        }
        return face;
    }
}
