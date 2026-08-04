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

    private PlayerSkinTextureNormalizer() {
    }


    public static byte[] normalizePng(byte[] pngBytes) throws IOException {
        return normalizePng(pngBytes, false);
    }


    public static byte[] normalizeFeaturePreservingPng(byte[] pngBytes) throws IOException {
        return normalizePng(pngBytes, true);
    }


    private static byte[] normalizePng(byte[] pngBytes, boolean preserveFeatureAlpha)
            throws IOException {
        Objects.requireNonNull(pngBytes, "pngBytes");
        BufferedImage decoded;
        try (ByteArrayInputStream input = new ByteArrayInputStream(pngBytes)) {
            decoded = ImageIO.read(input);
        }
        if (decoded == null) {
            throw new IOException("Player skin is not a decodable PNG image");
        }

        BufferedImage normalized = normalize(decoded, preserveFeatureAlpha);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(normalized, "png", output)) {
            throw new IOException("No PNG writer is available");
        }
        return output.toByteArray();
    }


    public static BufferedImage normalize(BufferedImage source) throws IOException {
        return normalize(source, false);
    }


    public static BufferedImage normalizeFeaturePreserving(BufferedImage source)
            throws IOException {
        return normalize(source, true);
    }


    private static BufferedImage normalize(
            BufferedImage source, boolean preserveFeatureAlpha) throws IOException {
        Objects.requireNonNull(source, "source");
        int width = source.getWidth();
        int height = source.getHeight();
        if (width != SKIN_WIDTH || height != LEGACY_HEIGHT && height != MODERN_HEIGHT) {
            throw new IOException(
                    "Player skin must be 64x64 or legacy 64x32, got " + width + 'x' + height);
        }

        boolean legacy = height == LEGACY_HEIGHT;
        BufferedImage image = new BufferedImage(SKIN_WIDTH, MODERN_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        int[] original = source.getRGB(0, 0, SKIN_WIDTH, height, null, 0, SKIN_WIDTH);
        image.setRGB(0, 0, SKIN_WIDTH, height, original, 0, SKIN_WIDTH);

        if (legacy) {
            copyRect(image, 4, 16, 16, 32, 4, 4, true, false);
            copyRect(image, 8, 16, 16, 32, 4, 4, true, false);
            copyRect(image, 0, 20, 24, 32, 4, 12, true, false);
            copyRect(image, 4, 20, 16, 32, 4, 12, true, false);
            copyRect(image, 8, 20, 8, 32, 4, 12, true, false);
            copyRect(image, 12, 20, 16, 32, 4, 12, true, false);
            copyRect(image, 44, 16, -8, 32, 4, 4, true, false);
            copyRect(image, 48, 16, -8, 32, 4, 4, true, false);
            copyRect(image, 40, 20, 0, 32, 4, 12, true, false);
            copyRect(image, 44, 20, -8, 32, 4, 12, true, false);
            copyRect(image, 48, 20, -16, 32, 4, 12, true, false);
            copyRect(image, 52, 20, -8, 32, 4, 12, true, false);
        }

        if (legacy) {
            clearFullyOpaqueLegacyOverlay(image, 32, 0, 64, 32);
        }
        if (!preserveFeatureAlpha) {
            setOpaque(image, 8, 0, 24, 8);
            setOpaque(image, 0, 8, 32, 16);
            setOpaque(image, 4, 16, 12, 20);
            setOpaque(image, 20, 16, 36, 20);
            setOpaque(image, 44, 16, 52, 20);
            setOpaque(image, 0, 20, 64, 32);
            setOpaque(image, 20, 48, 28, 52);
            setOpaque(image, 36, 48, 44, 52);
            setOpaque(image, 16, 52, 48, 64);
        }
        return image;
    }


    private static void copyRect(
            BufferedImage image,
            int sourceX,
            int sourceY,
            int translateX,
            int translateY,
            int width,
            int height,
            boolean mirrorX,
            boolean mirrorY) {
        int[] pixels = image.getRGB(sourceX, sourceY, width, height, null, 0, width);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int targetX = sourceX + translateX + (mirrorX ? width - 1 - x : x);
                int targetY = sourceY + translateY + (mirrorY ? height - 1 - y : y);
                image.setRGB(targetX, targetY, pixels[y * width + x]);
            }
        }
    }

    private static void clearFullyOpaqueLegacyOverlay(
            BufferedImage image, int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (alpha(image.getRGB(x, y)) < 128) {
                    return;
                }
            }
        }
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                image.setRGB(x, y, image.getRGB(x, y) & 0x00FFFFFF);
            }
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

    private static int alpha(int argb) {
        return argb >>> 24;
    }
}
