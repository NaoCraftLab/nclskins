package com.naocraftlab.skins.core.png;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;

public final class GalleryCapeComparison {
    private static final byte[] TRANSPARENT = new byte[4];
    private GalleryCapeComparison() {}

    public static String key(PreparedCapeAsset asset) throws IOException {
        try (var backing = asset.openBackingLease()) { return key(asset, backing); }
    }

    public static String key(PreparedCapeAsset asset, PreparedCapeAsset.BackingLease backing) throws IOException {
        Scan scan = new Scan(asset);
        while (!scan.advance(backing, 32)) { }
        return scan.key();
    }

    public static final class Scan {
        private final PreparedCapeAsset asset;
        private final MessageDigest digest;
        private final byte[] row;
        private int frame;
        private int y;
        private String key;

        public Scan(PreparedCapeAsset asset) {
            this.asset = java.util.Objects.requireNonNull(asset);
            digest = header(asset.width(), asset.height(), asset.frameCount(), asset.repeatCount());
            row = new byte[asset.width() * 4];
        }

        public boolean advance(PreparedCapeAsset.BackingLease backing, int rows) throws IOException {
            if (rows < 1) throw new IllegalArgumentException("Invalid comparison quantum");
            for (int read = 0; read < rows && frame < asset.frameCount(); read++) {
                backing.readRow(frame, y, row);
                update(digest, row, y++, asset.width());
                if (y == asset.height()) {
                    if (asset.frameCount() > 1) integer(digest, asset.delaysMillis().get(frame));
                    frame++;
                    y = 0;
                }
            }
            if (frame == asset.frameCount() && key == null) key = HexFormat.of().formatHex(digest.digest());
            return key != null;
        }

        public String key() {
            if (key == null) throw new IllegalStateException("Incomplete comparison");
            return key;
        }
    }

    public static String key(byte[] png) throws PngValidationException {
        byte[] canonical = new PngValidator().projectCanonicalCape(png).bytes();
        try {
            var image = ImageIO.read(new ByteArrayInputStream(canonical));
            int width = image.getWidth();
            MessageDigest digest = header(width, image.getHeight(), 1, 0);
            byte[] row = new byte[width * 4];
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = image.getRGB(x, y);
                    row[x * 4] = (byte) (pixel >>> 16);
                    row[x * 4 + 1] = (byte) (pixel >>> 8);
                    row[x * 4 + 2] = (byte) pixel;
                    row[x * 4 + 3] = (byte) (pixel >>> 24);
                }
                update(digest, row, y, width);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException failure) {
            throw new PngValidationException(PngValidationException.Reason.DECODE_FAILED, "Invalid comparison image");
        }
    }

    private static MessageDigest header(int width, int height, int frames, int repeats) {
        MessageDigest digest = PreparedCapeAsset.sha256Digest();
        digest.update("NCL-GALLERY-UV-1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        integer(digest, width); integer(digest, height); integer(digest, frames);
        integer(digest, frames == 1 ? 0 : repeats);
        return digest;
    }

    private static void integer(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(4).putInt(value).array());
    }

    private static void update(MessageDigest digest, byte[] row, int y, int width) {
        int scale = width / 64;
        int v = y / scale;
        for (int x = 0; x < width; x++) {
            int u = x / scale;
            boolean cape = v == 0 ? u >= 1 && u < 21 : v < 17 && u < 22;
            boolean wing = v < 2 ? u >= 24 && u < 44 : v < 22 && u >= 22 && u < 46;
            if (!cape && !wing) continue;
            int offset = x * 4;
            if (row[offset + 3] == 0) digest.update(TRANSPARENT);
            else digest.update(row, offset, 4);
        }
    }
}
