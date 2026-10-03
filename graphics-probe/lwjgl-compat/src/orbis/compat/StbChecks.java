package orbis.compat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.lwjgl.stb.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Small deterministic STB contract used by the host fixture runner and Orbis probe. */
public final class StbChecks {
    private StbChecks() {}

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static ByteBuffer file(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer out = MemoryUtil.memAlloc(bytes.length);
        out.put(bytes).flip();
        return out;
    }

    public static void run(Path fixtures) throws IOException {
        checkImage(fixtures);
        checkFont(fixtures);
        checkVorbis(fixtures);
        checkCallbacks(fixtures);
    }

    public static void checkImage(Path fixtures) throws IOException {
        ByteBuffer png = file(fixtures.resolve("pixels.png"));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1), channels = stack.mallocInt(1);
            try {
                for (int iteration = 0; iteration < 16; iteration++) {
                    ByteBuffer decoded = STBImage.stbi_load_from_memory(png, width, height, channels, 4);
                    require(decoded != null, "PNG decode: " + STBImage.stbi_failure_reason());
                    try {
                        require(width.get(0) == 2 && height.get(0) == 2 && channels.get(0) == 4,
                                "PNG dimensions/channels");
                        int[] expected = {0xff0000ff, 0x00ff00ff, 0x0000ffff, 0xffffffff};
                        for (int p = 0; p < 4; p++) {
                            int actual = 0;
                            for (int c = 0; c < 4; c++) actual = (actual << 8) | (decoded.get(p * 4 + c) & 255);
                            require(actual == expected[p], "PNG pixel " + p);
                        }
                        ByteBuffer resized = MemoryUtil.memAlloc(4 * 4 * 4);
                        try {
                            require(STBImageResize.stbir_resize_uint8(decoded, 2, 2, 0, resized, 4, 4, 0, 4),
                                    "resize");
                            require(resized.capacity() == 64 && (resized.get(3) & 255) == 255, "resize output");
                        } finally { MemoryUtil.memFree(resized); }
                    } finally { STBImage.stbi_image_free(decoded); }
                }
                ByteBuffer malformed = MemoryUtil.memAlloc(8);
                try {
                    malformed.putLong(0, 0x0123456789abcdefL);
                    ByteBuffer result = STBImage.stbi_load_from_memory(malformed, width, height, channels, 4);
                    if (result != null) STBImage.stbi_image_free(result);
                    require(result == null, "malformed image accepted");
                } finally { MemoryUtil.memFree(malformed); }
            } finally { MemoryUtil.memFree(png); }
        }
    }

    public static void checkCallbacks(Path fixtures) throws IOException {
        ByteBuffer png = file(fixtures.resolve("pixels.png"));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1), channels = stack.mallocInt(1);
            try {
                ByteBuffer decoded = STBImage.stbi_load_from_memory(png, width, height, channels, 4);
                require(decoded != null, "callback source PNG");
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (STBIWriteCallback write = STBIWriteCallback.create((context, data, size) -> {
                        ByteBuffer part = MemoryUtil.memByteBuffer(data, size);
                        byte[] copy = new byte[size];
                        part.get(copy);
                        bytes.write(copy, 0, size);
                    })) {
                        require(STBImageWrite.stbi_write_png_to_func(write, 0, 2, 2, 4, decoded, 0),
                                "PNG callback write");
                    }
                    ByteBuffer roundtrip = MemoryUtil.memAlloc(bytes.size());
                    try {
                        roundtrip.put(bytes.toByteArray()).flip();
                        ByteBuffer written = STBImage.stbi_load_from_memory(roundtrip, width, height, channels, 4);
                        require(written != null, "PNG callback roundtrip decode");
                        try {
                            require(width.get(0) == 2 && height.get(0) == 2, "callback roundtrip dimensions");
                            require((written.get(0) & 255) == 255 && (written.get(3) & 255) == 255,
                                    "callback roundtrip pixel");
                        } finally { STBImage.stbi_image_free(written); }
                    } finally { MemoryUtil.memFree(roundtrip); }
                } finally { STBImage.stbi_image_free(decoded); }
            } finally { MemoryUtil.memFree(png); }
        }
    }

    public static void checkFont(Path fixtures) throws IOException {
        ByteBuffer data = file(fixtures.resolve("DejaVuSans.ttf"));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            STBTTFontinfo info = STBTTFontinfo.callocStack(stack);
            try {
                require(STBTruetype.stbtt_InitFont(info, data), "font initialization");
                float scale = STBTruetype.stbtt_ScaleForPixelHeight(info, 24);
                require(scale > 0 && scale < 1, "font scale");
                IntBuffer advance = stack.mallocInt(1), bearing = stack.mallocInt(1);
                STBTruetype.stbtt_GetCodepointHMetrics(info, 'A', advance, bearing);
                require(advance.get(0) == 1401 && bearing.get(0) == 16,
                        "font metrics: advance=" + advance.get(0) + " bearing=" + bearing.get(0));
                IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1);
                ByteBuffer glyph = STBTruetype.stbtt_GetCodepointBitmap(info, scale, scale, 'A',
                        width, height, null, null);
                require(glyph != null, "glyph raster");
                try {
                    require(width.get(0) == 14 && height.get(0) == 16 && glyph.remaining() == 224,
                            "glyph bounds: " + width.get(0) + "x" + height.get(0));
                    boolean ink = false;
                    for (int i = 0; i < glyph.remaining(); i++) ink |= glyph.get(i) != 0;
                    require(ink, "glyph ink");
                } finally { STBTruetype.stbtt_FreeBitmap(glyph, 0); }
            } finally { MemoryUtil.memFree(data); }
        }
    }

    public static void checkVorbis(Path fixtures) throws IOException {
        ByteBuffer data = file(fixtures.resolve("tone.ogg"));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            try {
                IntBuffer error = stack.mallocInt(1);
                long decoder = STBVorbis.stb_vorbis_open_memory(data, error, null);
                require(decoder != 0, "Vorbis open error " + error.get(0));
                try {
                    STBVorbisInfo info = STBVorbisInfo.callocStack(stack);
                    STBVorbis.stb_vorbis_get_info(decoder, info);
                    require(info.sample_rate() == 8000 && info.channels() == 1, "Vorbis format");
                    ShortBuffer pcm = stack.mallocShort(2048);
                    int frames = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, 1, pcm);
                    require(frames > 0, "Vorbis has no PCM");
                    boolean signal = false;
                    for (int i = 0; i < frames; i++) signal |= pcm.get(i) != 0;
                    require(signal, "Vorbis decoded silence");
                } finally { STBVorbis.stb_vorbis_close(decoder); }
                ByteBuffer invalid = MemoryUtil.memAlloc(8);
                try {
                    invalid.putLong(0, 0x0123456789abcdefL);
                    long bad = STBVorbis.stb_vorbis_open_memory(invalid, error, null);
                    if (bad != 0) STBVorbis.stb_vorbis_close(bad);
                    require(bad == 0, "malformed Vorbis accepted");
                } finally { MemoryUtil.memFree(invalid); }
            } finally { MemoryUtil.memFree(data); }
        }
    }
}
