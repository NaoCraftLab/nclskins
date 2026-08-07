package com.naocraftlab.skins.client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;


public final class PlayerSkinTextureNormalizer {
    private static final int SKIN_WIDTH = 64;
    private static final int LEGACY_HEIGHT = 32;
    private static final int MODERN_HEIGHT = 64;
    private PlayerSkinTextureNormalizer() {
    }

    public static byte[] normalizePng(byte[] pngBytes) throws IOException {
        Objects.requireNonNull(pngBytes, "pngBytes");
        BufferedImage decoded;
        try (ByteArrayInputStream input = new ByteArrayInputStream(pngBytes)) {
            decoded = ImageIO.read(input);
        }
        if (decoded == null) {
            throw new IOException("Player skin is not a decodable PNG image");
        }
        requirePlayerSkinDimensions(decoded);
        return pngBytes.clone();
    }

    public static BufferedImage normalize(BufferedImage source) throws IOException {
        Objects.requireNonNull(source, "source");
        requirePlayerSkinDimensions(source);
        return copy(source);
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] pixels = source.getRGB(
                0, 0, source.getWidth(), source.getHeight(), null, 0, source.getWidth());
        copy.setRGB(0, 0, source.getWidth(), source.getHeight(), pixels, 0, source.getWidth());
        return copy;
    }

    private static void requirePlayerSkinDimensions(BufferedImage image) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width != SKIN_WIDTH || height != LEGACY_HEIGHT && height != MODERN_HEIGHT) {
            throw new IOException(
                    "Player skin must be 64x64 or legacy 64x32, got " + width + 'x' + height);
        }
    }
}
