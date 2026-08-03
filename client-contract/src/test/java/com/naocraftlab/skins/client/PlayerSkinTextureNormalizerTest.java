package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerSkinTextureNormalizerTest {
    @Test
    void preservesTransparentFeaturePixelsOutsideVanillaBaseUvs() throws IOException {
        BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x00112233);
        source.setRGB(8, 0, 0x00123456);

        BufferedImage normalized = PlayerSkinTextureNormalizer.normalize(source);

        assertEquals(0x00112233, normalized.getRGB(0, 0));
        assertEquals(0xFF123456, normalized.getRGB(8, 0));
    }

    @Test
    void pngRoundTripPreservesRgbStoredUnderTransparentFeaturePixels() throws IOException {
        BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x00112233);

        byte[] normalizedPng = PlayerSkinTextureNormalizer.normalizePng(writePng(source));
        BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(normalizedPng));

        assertEquals(0x00112233, normalized.getRGB(0, 0));
    }

    @Test
    void expandsLegacyLimbsWithTheCanonicalMirroredCopies() throws IOException {
        BufferedImage legacy = coordinateImage(64, 32, 0x40);

        BufferedImage normalized = PlayerSkinTextureNormalizer.normalize(legacy);

        assertEquals(64, normalized.getWidth());
        assertEquals(64, normalized.getHeight());
        assertRgbEquals(legacy.getRGB(7, 16), normalized.getRGB(20, 48));
        assertRgbEquals(legacy.getRGB(4, 16), normalized.getRGB(23, 48));
        assertRgbEquals(legacy.getRGB(47, 16), normalized.getRGB(36, 48));
        assertRgbEquals(legacy.getRGB(44, 16), normalized.getRGB(39, 48));
        assertEquals(0xFF, alpha(normalized.getRGB(20, 48)));
        assertEquals(0xFF, alpha(normalized.getRGB(36, 48)));
    }

    @Test
    void clearsThePreAlphaLegacyOverlayOnlyWhenItIsFullyOpaque() throws IOException {
        BufferedImage legacy = coordinateImage(64, 32, 0xFF);

        BufferedImage normalized = PlayerSkinTextureNormalizer.normalize(legacy);

        assertEquals(0, alpha(normalized.getRGB(40, 8)));
        assertEquals(0xFF, alpha(normalized.getRGB(44, 16)));
        assertEquals(0xFF, alpha(normalized.getRGB(40, 20)));
    }

    @Test
    void rejectsNonPlayerDimensions() {
        BufferedImage invalid = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);

        assertThrows(IOException.class, () -> PlayerSkinTextureNormalizer.normalize(invalid));
    }

    private static BufferedImage coordinateImage(int width, int height, int alpha) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, alpha << 24 | x << 8 | y);
            }
        }
        return image;
    }

    private static byte[] writePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG writer is available for the test");
        }
        return output.toByteArray();
    }

    private static void assertRgbEquals(int expected, int actual) {
        assertEquals(expected & 0x00FFFFFF, actual & 0x00FFFFFF);
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }
}
