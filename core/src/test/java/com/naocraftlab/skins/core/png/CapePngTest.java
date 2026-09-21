package com.naocraftlab.skins.core.png;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapePngTest {
    private final PngValidator validator = new PngValidator();

    @Test void capePixelsAreNotExpandedOrChanged() throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 5, 0x7f123456);
        var cape = validator.projectCape(png(image));
        var decoded = ImageIO.read(new java.io.ByteArrayInputStream(cape.bytes()));
        assertEquals(32, decoded.getHeight());
        assertEquals(0x7f123456, decoded.getRGB(3, 5));
        assertFalse(cape.hasElytra());
    }

    @Test void wingPixelsAreDetectedButUnusedAtlasPixelsAreNot() throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(60, 30, 0xffffffff);
        image.setRGB(22, 0, 0xffffffff);
        assertFalse(validator.projectCape(png(image)).hasElytra());
        image.setRGB(24, 0, 0x01000000);
        assertTrue(validator.projectCape(png(image)).hasElytra());
    }

    @Test void wrongSizeIsRejected() {
        assertThrows(PngValidationException.class, () -> validator.projectCape(png(new BufferedImage(128, 64, 2))));
        assertThrows(PngValidationException.class, () -> validator.projectCape(png(new BufferedImage(64, 64, 2))));
    }

    @Test void transparentRgbDoesNotCreateAnotherIdentity() throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        String initial = validator.projectCape(png(image)).renderSha256();
        image.setRGB(5, 5, 0x00123456);
        assertEquals(initial, validator.projectCape(png(image)).renderSha256());
    }

    @Test void malformedOversizedAndAnimatedPngAreRejected() throws Exception {
        assertThrows(PngValidationException.class, () -> validator.projectCape(new byte[64]));
        assertThrows(PngValidationException.class, () -> validator.projectCape(new byte[PngValidator.DEFAULT_MAX_BYTES + 1]));
        byte[] valid = png(new BufferedImage(64, 32, 2));
        var chunk = java.nio.ByteBuffer.allocate(20).putInt(8).putInt(0x6163544c).putInt(1).putInt(0);
        var crc = new java.util.zip.CRC32(); crc.update(chunk.array(), 4, 12); chunk.putInt((int) crc.getValue());
        var animated = new ByteArrayOutputStream();
        animated.write(valid, 0, 33); animated.write(chunk.array()); animated.write(valid, 33, valid.length - 33);
        assertThrows(PngValidationException.class, () -> validator.projectCape(animated.toByteArray()));
        valid[valid.length - 1] ^= 1;
        assertThrows(PngValidationException.class, () -> validator.projectCape(valid));
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
