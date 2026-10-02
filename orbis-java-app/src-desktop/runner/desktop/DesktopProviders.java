package runner.desktop;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import sun.awt.image.ImagingLib;
import sun.font.FontManagerFactory;

/** One bounded startup exercise through JDK-owned drawing and image APIs. */
final class DesktopProviders {
    private static final int SIDE = 32;
    private static final int MAX_FONT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_JPEG_BYTES = 64 * 1024;

    private DesktopProviders() { }

    static void validate(Path home) {
        capability("software drawing", home + "/lib/libawt.so, "
                + home + "/lib/libawt_headless.so", () -> {
            BufferedImage image = image();
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, SIDE, SIDE);
                graphics.setColor(Color.BLACK);
                graphics.drawLine(0, 0, SIDE - 1, SIDE - 1);
                require(image.getRGB(0, 0) == Color.BLACK.getRGB()
                        && image.getRGB(SIDE - 1, 0) == Color.WHITE.getRGB(),
                        "software drawing did not update pixels");
            } finally {
                graphics.dispose();
            }
        });
        Path font = home.resolve("lib/fonts/DejaVuSans.ttf");
        capability("baseline TrueType font and glyph rasterization", font + ", "
                + home + "/lib/libfontmanager.so, " + home + "/lib/libfreetype.so", () -> {
            byte[] bytes;
            try (InputStream stream = Files.newInputStream(font)) {
                bytes = stream.readNBytes(MAX_FONT_BYTES + 1);
            }
            require(bytes.length > 0 && bytes.length <= MAX_FONT_BYTES,
                    "baseline font must contain 1.." + MAX_FONT_BYTES + " bytes");
            require("sun.font.OrbisFontManager".equals(
                    FontManagerFactory.getInstance().getClass().getName()),
                    "OrbisFontManager was not selected");
            // Parsing the bounded installed bytes also avoids accepting a
            // fallback logical font when the configured TTF is malformed.
            Font baseline = Font.createFont(Font.TRUETYPE_FONT,
                    new ByteArrayInputStream(bytes)).deriveFont(20f);
            require("DejaVu Sans".equals(baseline.getFamily(java.util.Locale.ROOT)),
                    "baseline TTF is not DejaVu Sans");
            drawGlyph(baseline);
            drawGlyph(new Font(Font.DIALOG, Font.PLAIN, 20));
        });
        capability("JPEG writer and reader", home + "/lib/libjavajpeg.so", () -> jpeg());
        capability("ICC color conversion", home + "/lib/liblcms.so", () -> {
            // Distinct source/destination profiles prohibit the JDK's
            // identical-profile drawing-only shortcut and execute LCMS.
            ICC_Profile srgb = ICC_Profile.getInstance(ColorSpace.CS_sRGB);
            ICC_Profile linear = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB);
            BufferedImage source = image();
            source.setRGB(0, 0, 0xff808040);
            BufferedImage converted = new ColorConvertOp(
                    new ICC_Profile[] {srgb, linear}, null).filter(source, null);
            require(converted.getWidth() == SIDE && converted.getHeight() == SIDE
                    && converted.getRaster().getSample(0, 0, 0) > 0
                    && converted.getRaster().getSample(0, 0, 0)
                        < source.getRaster().getSample(0, 0, 0),
                    "sRGB to linear RGB conversion did not produce linear samples");
        });
        capability("native image filtering", home + "/lib/libmlib_image.so", () -> {
            BufferedImage source = image();
            source.setRGB(0, 0, 0xff123456);
            BufferedImage destination = image();
            AffineTransformOp identity = new AffineTransformOp(
                    new AffineTransform(), AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
            // AffineTransformOp.filter can silently fall back. This exported
            // JDK hook returns null when mlib load/init/filtering is disabled.
            require(ImagingLib.filter(identity, source, destination) == destination
                    && destination.getRGB(0, 0) == source.getRGB(0, 0),
                    "JDK ImagingLib native filter unavailable or rejected the image");
        });
    }

    private static BufferedImage image() {
        return new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
    }

    private static void drawGlyph(Font font) {
        BufferedImage image = image();
        Graphics2D graphics = image.createGraphics();
        try {
            require(font.canDisplay('A'), "baseline glyph A is unavailable");
            graphics.setFont(font);
            graphics.setColor(Color.WHITE);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            require(!font.createGlyphVector(graphics.getFontRenderContext(), "A")
                    .getOutline().getBounds2D().isEmpty(), "font scaler returned an empty glyph");
            graphics.drawString("A", 2, 24);
            for (int y = 0; y < SIDE; y++) {
                for (int x = 0; x < SIDE; x++) {
                    if (image.getRGB(x, y) != Color.BLACK.getRGB()) return;
                }
            }
            throw new IllegalStateException("font scaler produced no glyph pixels");
        } finally {
            graphics.dispose();
        }
    }

    private static void jpeg() throws Exception {
        ImageWriter writer = null;
        ImageReader reader = null;
        try {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("JPEG");
            while (writers.hasNext()) {
                ImageWriter candidate = writers.next();
                require(candidate != null, "JPEG writer instance creation failed");
                if ("com.sun.imageio.plugins.jpeg.JPEGImageWriter".equals(
                        candidate.getClass().getName()) && candidate.getClass().getClassLoader() == null) {
                    writer = candidate;
                    break;
                }
                candidate.dispose();
            }
            require(writer != null, "owning JDK JPEG writer unavailable");
            BoundedJpegOutput bytes = new BoundedJpegOutput();
            try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(image(), null, null), writer.getDefaultWriteParam());
            }
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("JPEG");
            while (readers.hasNext()) {
                ImageReader candidate = readers.next();
                require(candidate != null, "JPEG reader instance creation failed");
                if ("com.sun.imageio.plugins.jpeg.JPEGImageReader".equals(
                        candidate.getClass().getName()) && candidate.getClass().getClassLoader() == null) {
                    reader = candidate;
                    break;
                }
                candidate.dispose();
            }
            require(reader != null, "owning JDK JPEG reader unavailable");
            try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                    new ByteArrayInputStream(bytes.toByteArray()))) {
                reader.setInput(input, true, true);
                require(reader.getWidth(0) == SIDE && reader.getHeight(0) == SIDE,
                        "JPEG dimensions changed");
                BufferedImage decoded = reader.read(0);
                require(decoded != null && decoded.getWidth() == SIDE && decoded.getHeight() == SIDE,
                        "JPEG decoding failed");
            }
        } finally {
            if (reader != null) reader.dispose();
            if (writer != null) writer.dispose();
        }
    }

    private static final class BoundedJpegOutput extends ByteArrayOutputStream {
        BoundedJpegOutput() { super(MAX_JPEG_BYTES); }
        @Override public synchronized void write(int value) {
            require(count < MAX_JPEG_BYTES, "JPEG startup output exceeds 64 KiB");
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            require(length >= 0 && length <= MAX_JPEG_BYTES - count,
                    "JPEG startup output exceeds 64 KiB");
            super.write(bytes, offset, length);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void capability(String name, String artifacts, Operation operation) {
        try {
            operation.run();
        } catch (Throwable failure) {
            throw new IllegalStateException("Desktop capability " + name
                    + " failed; artifacts: " + artifacts, failure);
        }
    }

    @FunctionalInterface private interface Operation { void run() throws Exception; }
}
