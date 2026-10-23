package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerSkinTextureNormalizerTest {
    @Test
    void ordinaryModernSkinReachesTheNativeLoaderByteForByte() throws IOException {
        BufferedImage source = coordinateImage(64, 64, 0x12);
        source.setRGB(0, 0, 0x00112233);
        byte[] png = writePng(source);

        byte[] normalized = PlayerSkinTextureNormalizer.normalizePng(png);

        assertArrayEquals(png, normalized);
        assertEquals(0x12, alpha(read(normalized).getRGB(8, 0)));
    }

    @Test
    void ordinaryLegacySkinReachesTheNativeLoaderByteForByte() throws IOException {
        BufferedImage source = coordinateImage(64, 32, 0x12);
        byte[] png = writePng(source);

        byte[] normalized = PlayerSkinTextureNormalizer.normalizePng(png);

        assertArrayEquals(png, normalized);
        assertEquals(32, read(normalized).getHeight());
    }

    @Test
    void featureMarkerAndEmbeddedAlphaArePreservedWithoutFormatRecognition() throws IOException {
        BufferedImage source = coordinateImage(64, 64, 0x12);
        addEtfMarker(source);
        byte[] png = writePng(source);

        assertArrayEquals(png, PlayerSkinTextureNormalizer.normalizePng(png));
        assertEquals(0x12, alpha(read(png).getRGB(8, 0)));
    }

    @Test
    void incompleteFeatureMarkerAndArbitraryAlphaAreAlsoPreserved() throws IOException {
        BufferedImage source = coordinateImage(64, 64, 0x12);
        addEtfMarker(source);
        source.setRGB(3, 18, 0xFF000000);

        byte[] png = writePng(source);
        byte[] normalized = PlayerSkinTextureNormalizer.normalizePng(png);

        assertArrayEquals(png, normalized);
        assertEquals(0x12, alpha(read(normalized).getRGB(8, 0)));
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

    private static BufferedImage read(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static void addEtfMarker(BufferedImage image) {
        image.setRGB(1, 16, 0xFF0000FF);
        image.setRGB(0, 16, 0xFF00007F);
        image.setRGB(0, 17, 0xFF0000FF);
        image.setRGB(2, 16, 0xFF00FF00);
        image.setRGB(3, 16, 0xFF007F00);
        image.setRGB(3, 17, 0xFF00FF00);
        image.setRGB(0, 18, 0xFFFF0000);
        image.setRGB(0, 19, 0xFF7F0000);
        image.setRGB(1, 19, 0xFFFF0000);
        image.setRGB(2, 19, 0xFFFFFFFF);
        image.setRGB(3, 18, 0xFFFFFFFF);
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }
}
