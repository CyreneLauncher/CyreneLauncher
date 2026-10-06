package orbis.launcher;

import orbis.launcher.qr.QrCode;

/** Encodes Microsoft's verification page only; the visible user code is entered on that page. */
final class LoginQr {
    private LoginQr() { }

    static boolean[][] encode(String verificationUri) {
        QrCode qr = QrCode.encodeText(verificationUri, QrCode.Ecc.MEDIUM);
        boolean[][] modules = new boolean[qr.size][qr.size];
        for (int y = 0; y < qr.size; y++)
            for (int x = 0; x < qr.size; x++) modules[y][x] = qr.getModule(x, y);
        return modules;
    }

    /** Integer scaling plus four-module quiet zone; no image codecs or AWT. */
    static void draw(Renderer renderer, boolean[][] modules, float x, float y, float maxSize) {
        int size = modules.length;
        int scale = Math.max(1, (int) (maxSize / (size + 8)));
        float extent = (size + 8) * scale;
        renderer.rect(x, y, extent, extent, 0xffffffff);
        for (int row = 0; row < size; row++)
            for (int col = 0; col < size; col++)
                if (modules[row][col]) renderer.rect(x + (col + 4) * scale, y + (row + 4) * scale,
                        scale, scale, 0xff000000);
    }
}
