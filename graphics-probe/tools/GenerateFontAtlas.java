import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Build-time rasterization: the console loads PNG pixels and advances, never a system font. */
public final class GenerateFontAtlas {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        Font font;
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            font = Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(32f);
        }
        BufferedImage atlas = new BufferedImage(640, 640, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();
        graphics.setFont(font);
        graphics.setColor(Color.WHITE);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        FontMetrics metrics = graphics.getFontMetrics();
        try (var advances = new DataOutputStream(Files.newOutputStream(output.resolve("advances.bin")))) {
            for (int c = 0; c < 256; c++) {
                char glyph = font.canDisplay((char) c) ? (char) c : '?';
                if (c >= 32 && (c < 127 || c >= 160))
                    graphics.drawString(String.valueOf(glyph), c % 16 * 40 + 2, c / 16 * 40 + 31);
                advances.writeFloat((metrics.charWidth(glyph) + 0.5f) / 4f);
            }
        }
        graphics.dispose();
        ImageIO.write(atlas, "png", output.resolve("atlas.png").toFile());
    }
}
