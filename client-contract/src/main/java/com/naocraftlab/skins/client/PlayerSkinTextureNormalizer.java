package com.naocraftlab.skins.client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;


public final class PlayerSkinTextureNormalizer {
    private static final int SKIN_WIDTH = 64;
    private static final int LEGACY_HEIGHT = 32;
    private static final int MODERN_HEIGHT = 64;
    private static final MarkerPixel[] ETF_FEATURE_MARKER = {
            new MarkerPixel(1, 16, 0xFF0000FF),
            new MarkerPixel(0, 16, 0xFF00007F),
            new MarkerPixel(0, 17, 0xFF0000FF),
            new MarkerPixel(2, 16, 0xFF00FF00),
            new MarkerPixel(3, 16, 0xFF007F00),
            new MarkerPixel(3, 17, 0xFF00FF00),
            new MarkerPixel(0, 18, 0xFFFF0000),
            new MarkerPixel(0, 19, 0xFF7F0000),
            new MarkerPixel(1, 19, 0xFFFF0000),
            new MarkerPixel(2, 19, 0xFFFFFFFF),
            new MarkerPixel(3, 18, 0xFFFFFFFF)
    };

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
        if (hasCompleteEtfFeatureMarker(decoded)) {
            return pngBytes.clone();
        }

        BufferedImage normalized = normalize(decoded);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(normalized, "png", output)) {
            throw new IOException("No PNG writer is available");
        }
        return output.toByteArray();
    }

    public static BufferedImage normalize(BufferedImage source) throws IOException {
        Objects.requireNonNull(source, "source");
        requirePlayerSkinDimensions(source);
        BufferedImage image = copy(source);
        if (hasCompleteEtfFeatureMarker(image)) {
            return image;
        }

        setOpaque(image, 8, 0, 24, 8);
        setOpaque(image, 0, 8, 32, 16);
        setOpaque(image, 4, 16, 12, 20);
        setOpaque(image, 20, 16, 36, 20);
        setOpaque(image, 44, 16, 52, 20);
        setOpaque(image, 0, 20, 64, 32);
        if (image.getHeight() == MODERN_HEIGHT) {
            setOpaque(image, 20, 48, 28, 52);
            setOpaque(image, 36, 48, 44, 52);
            setOpaque(image, 16, 52, 48, 64);
        }
        return image;
    }

    public static boolean hasCompleteEtfFeatureMarker(BufferedImage image) throws IOException {
        Objects.requireNonNull(image, "image");
        requirePlayerSkinDimensions(image);
        for (MarkerPixel marker : ETF_FEATURE_MARKER) {
            if (image.getRGB(marker.x(), marker.y()) != marker.argb()) {
                return false;
            }
        }
        return true;
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

    private static void setOpaque(
            BufferedImage image, int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                image.setRGB(x, y, image.getRGB(x, y) | 0xFF000000);
            }
        }
    }

    private record MarkerPixel(int x, int y, int argb) {
    }
}
