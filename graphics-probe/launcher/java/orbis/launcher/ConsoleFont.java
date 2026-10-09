package orbis.launcher;

/**
 * The launcher's own text font when no game JAR supplies Minecraft's ascii.png: the public-domain
 * Lat15-VGA8 8x8 glyphs the runner's screen console uses (orbis-java-app/tools/orbis_screen_font.h),
 * laid out as ascii.png is: 16x16 cells of 8 pixels, white glyphs on transparency.
 */
final class ConsoleFont {
    /** Characters 32 to 126; each long holds eight rows, top row first, leftmost pixel in bit 7. */
    private static final long[] GLYPHS = {
            0x0000000000000000L, 0x183c3c1818001800L, 0x6666240000000000L, 0x6c6cfe6cfe6c6c00L, 0x183e603c067c1800L, 0x00c6cc183066c600L,
            0x386c3876dccc7600L, 0x1818300000000000L, 0x0c18303030180c00L, 0x30180c0c0c183000L, 0x00663cff3c660000L, 0x0018187e18180000L,
            0x0000000000181830L, 0x0000007e00000000L, 0x0000000000181800L, 0x060c183060c08000L, 0x386cc6d6c66c3800L, 0x1838181818187e00L,
            0x7cc6061c3066fe00L, 0x7cc6063c06c67c00L, 0x1c3c6cccfe0c1e00L, 0xfec0c0fc06c67c00L, 0x3860c0fcc6c67c00L, 0xfec60c1830303000L,
            0x7cc6c67cc6c67c00L, 0x7cc6c67e060c7800L, 0x0018180000181800L, 0x0018180000181830L, 0x060c1830180c0600L, 0x00007e00007e0000L,
            0x6030180c18306000L, 0x7cc60c1818001800L, 0x7cc6dededec07800L, 0x386cc6fec6c6c600L, 0xfc66667c6666fc00L, 0x3c66c0c0c0663c00L,
            0xf86c6666666cf800L, 0xfe6268786862fe00L, 0xfe6268786860f000L, 0x3c66c0c0ce663a00L, 0xc6c6c6fec6c6c600L, 0x3c18181818183c00L,
            0x1e0c0c0ccccc7800L, 0xe6666c786c66e600L, 0xf06060606266fe00L, 0xc6eefefed6c6c600L, 0xc6e6f6decec6c600L, 0x7cc6c6c6c6c67c00L,
            0xfc66667c6060f000L, 0x7cc6c6c6c6ce7c0eL, 0xfc66667c6c66e600L, 0x3c6630180c663c00L, 0x7e7e5a1818183c00L, 0xc6c6c6c6c6c67c00L,
            0xc6c6c6c6c66c3800L, 0xc6c6c6d6d6fe6c00L, 0xc6c66c386cc6c600L, 0x6666663c18183c00L, 0xfec68c183266fe00L, 0x3c30303030303c00L,
            0xc06030180c060200L, 0x3c0c0c0c0c0c3c00L, 0x10386cc600000000L, 0x00000000000000ffL, 0x30180c0000000000L, 0x0000780c7ccc7600L,
            0xe0607c666666dc00L, 0x00007cc6c0c67c00L, 0x1c0c7ccccccc7600L, 0x00007cc6fec07c00L, 0x3c6660f86060f000L, 0x000076cccc7c0cf8L,
            0xe0606c766666e600L, 0x1800381818183c00L, 0x060006060666663cL, 0xe060666c786ce600L, 0x3818181818183c00L, 0x0000ecfed6d6d600L,
            0x0000dc6666666600L, 0x00007cc6c6c67c00L, 0x0000dc66667c60f0L, 0x000076cccc7c0c1eL, 0x0000dc766060f000L, 0x00007ec07c06fc00L,
            0x3030fc3030361c00L, 0x0000cccccccc7600L, 0x0000c6c6c66c3800L, 0x0000c6d6d6fe6c00L, 0x0000c66c386cc600L, 0x0000c6c6c67e06fcL,
            0x00007e4c18327e00L, 0x0e18187018180e00L, 0x1818181818181800L, 0x7018180e18187000L, 0x76dc000000000000L
    };

    static final int SIZE = 128;

    private ConsoleFont() {
    }

    /** ARGB pixels of a SIZE x SIZE atlas. */
    static int[] atlas() {
        int[] argb = new int[SIZE * SIZE];
        for (int c = 32; c < 32 + GLYPHS.length; c++) {
            long glyph = GLYPHS[c - 32];
            for (int y = 0; y < 8; y++) {
                int row = (int) (glyph >>> (56 - 8 * y)) & 0xff;
                for (int x = 0; x < 8; x++)
                    if ((row & (0x80 >>> x)) != 0)
                        argb[((c / 16) * 8 + y) * SIZE + (c % 16) * 8 + x] = 0xffffffff;
            }
        }
        return argb;
    }
}
