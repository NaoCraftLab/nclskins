package com.naocraftlab.skins.core.png;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        assertEquals("6f5bc30a5d3af3a77f43569f4e14f9974ef0be5c8dd5c6e232ef11d3faf59fab", cape.renderSha256());
    }

    @Test void optifinePixelsArePaddedWithoutScalingOrAlphaChanges() throws Exception {
        BufferedImage image = new BufferedImage(46, 22, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x7f123456);
        image.setRGB(45, 21, 0x01123456);
        var cape = validator.projectImportedCape(png(image));
        var decoded = ImageIO.read(new java.io.ByteArrayInputStream(cape.bytes()));
        assertEquals(64, decoded.getWidth());
        assertEquals(32, decoded.getHeight());
        assertEquals(0x7f123456, decoded.getRGB(0, 0));
        assertEquals(0x01123456, decoded.getRGB(45, 21));
        assertEquals(0, decoded.getRGB(46, 21));
        assertEquals(0, decoded.getRGB(45, 22));
        assertEquals(0, decoded.getRGB(63, 31));
        assertTrue(cape.hasElytra());
        assertArrayEquals(validator.projectCape(cape.bytes()).bytes(), cape.bytes());
        assertEquals(cape.renderSha256(), validator.projectCape(png(image)).renderSha256());
    }

    @Test void importedCapeAndEquivalentPaddedCapeShareVisualIdentity() throws Exception {
        BufferedImage image = new BufferedImage(46, 22, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(4, 6, 0x80112233);
        image.setRGB(22, 3, 0xffabcdef);
        BufferedImage padded = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        padded.setRGB(0, 0, 46, 22, image.getRGB(0, 0, 46, 22, null, 0, 46), 0, 46);
        padded.setRGB(60, 30, 0x00123456);
        assertEquals(validator.projectCape(png(padded)).renderSha256(),
                validator.projectImportedCape(png(image)).renderSha256());
        assertEquals(validator.projectCape(png(image)).renderSha256(),
                validator.projectImportedCape(png(image)).renderSha256());
    }

    @Test void hdOptifinePixelsRetainEveryDetailAndSharePaddedIdentity() throws Exception {
        BufferedImage source = new BufferedImage(92, 44, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x80112233);
        source.setRGB(1, 0, 0xff445566);
        source.setRGB(0, 1, 0x01778899);
        source.setRGB(1, 1, 0xffaabbcc);
        source.setRGB(91, 43, 0x7f123456);
        BufferedImage padded = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
        padded.setRGB(0, 0, 92, 44, source.getRGB(0, 0, 92, 44, null, 0, 92), 0, 92);
        padded.setRGB(127, 63, 0x00123456);

        var imported = validator.projectImportedCape(png(source));
        var canonical = validator.projectCanonicalCape(png(padded));
        var decoded = ImageIO.read(new java.io.ByteArrayInputStream(imported.bytes()));
        assertEquals(128, decoded.getWidth());
        assertEquals(64, decoded.getHeight());
        assertEquals(0x80112233, decoded.getRGB(0, 0));
        assertEquals(0xff445566, decoded.getRGB(1, 0));
        assertEquals(0x01778899, decoded.getRGB(0, 1));
        assertEquals(0xffaabbcc, decoded.getRGB(1, 1));
        assertEquals(0x7f123456, decoded.getRGB(91, 43));
        assertEquals(0, decoded.getRGB(92, 43));
        assertEquals(0, decoded.getRGB(91, 44));
        assertEquals(imported.renderSha256(), canonical.renderSha256());
        assertArrayEquals(imported.bytes(), validator.projectCanonicalCape(imported.bytes()).bytes());
        assertThrows(PngValidationException.class, () -> validator.projectCanonicalCape(png(source)));
        assertEquals(imported.renderSha256(), validator.projectCape(png(source)).renderSha256());
        assertEquals(canonical.renderSha256(), validator.projectCape(png(padded)).renderSha256());
    }

    @Test void hdIdentityIncludesDimensionsAndVisiblePixels() throws Exception {
        BufferedImage standard = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        BufferedImage hd = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
        String standardHash = validator.projectCape(png(standard)).renderSha256();
        String hdHash = validator.projectCanonicalCape(png(hd)).renderSha256();
        assertFalse(standardHash.equals(hdHash));
        hd.setRGB(5, 5, 0x00123456);
        assertEquals(hdHash, validator.projectCanonicalCape(png(hd)).renderSha256());
        hd.setRGB(5, 5, 0x01123456);
        assertFalse(hdHash.equals(validator.projectCanonicalCape(png(hd)).renderSha256()));
    }

    @Test void wingPixelsAreDetectedButUnusedAtlasPixelsAreNot() throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(60, 30, 0xffffffff);
        image.setRGB(22, 0, 0xffffffff);
        assertFalse(validator.projectCape(png(image)).hasElytra());
        image.setRGB(24, 0, 0x01000000);
        assertTrue(validator.projectCape(png(image)).hasElytra());
    }

    @Test void hdWingClassificationInspectsEachPixelWithoutDownsampling() throws Exception {
        BufferedImage image = new BufferedImage(92, 44, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(47, 0, 0xff123456);
        image.setRGB(44, 0, 0xff123456);
        assertFalse(validator.projectImportedCape(png(image)).hasElytra());
        image.setRGB(49, 1, 0x01000000);
        assertTrue(validator.projectImportedCape(png(image)).hasElytra());
        image.setRGB(49, 1, 0);
        image.setRGB(44, 5, 0x01000000);
        assertTrue(validator.projectImportedCape(png(image)).hasElytra());
    }

    @Test void wrongSizeIsRejected() {
        assertThrows(PngValidationException.class, () -> validator.projectCape(png(new BufferedImage(64, 64, 2))));
        assertThrows(PngValidationException.class, () -> validator.projectCape(png(new BufferedImage(184, 88, 2))));
        assertThrows(PngValidationException.class, () -> validator.projectCape(png(new BufferedImage(256, 128, 2))));
        assertThrows(PngValidationException.class, () -> validator.projectImportedCape(png(new BufferedImage(184, 88, 2))));
        assertThrows(PngValidationException.class, () -> validator.projectImportedCape(png(new BufferedImage(256, 128, 2))));
        assertThrows(PngValidationException.class, () -> validator.projectImportedCape(png(new BufferedImage(64, 64, 2))));
    }

    @Test void unsupportedCapeDimensionsRejectAtHeaderBeforeImageDecode() throws Exception {
        byte[] invalidDeflate = invalidDeflatePng(64, 64);
        assertEquals(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS,
                assertThrows(PngValidationException.class, () -> validator.projectCape(invalidDeflate)).reason());
        assertEquals(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS,
                assertThrows(PngValidationException.class, () -> validator.projectCanonicalCape(invalidDeflate)).reason());
        assertEquals(64, validator.validate(png(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB))).height());
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
        assertThrows(PngValidationException.class, () -> validator.projectImportedCape(valid));
        assertThrows(PngValidationException.class, () -> validator.projectImportedCape(new byte[64]));
        assertThrows(PngValidationException.class, () -> validator.projectImportedCape(new byte[PngValidator.DEFAULT_MAX_BYTES + 1]));
        byte[] narrow = png(new BufferedImage(46, 22, 2));
        var narrowAnimated = new ByteArrayOutputStream();
        narrowAnimated.write(narrow, 0, 33); narrowAnimated.write(chunk.array()); narrowAnimated.write(narrow, 33, narrow.length - 33);
        assertThrows(PngValidationException.class, () -> validator.projectImportedCape(narrowAnimated.toByteArray()));
        byte[] hd = png(new BufferedImage(92, 44, 2));
        var hdAnimated = new ByteArrayOutputStream();
        hdAnimated.write(hd, 0, 33); hdAnimated.write(chunk.array()); hdAnimated.write(hd, 33, hd.length - 33);
        assertThrows(PngValidationException.class, () -> validator.projectImportedCape(hdAnimated.toByteArray()));
        assertThrows(PngValidationException.class, () -> validator.projectCape(hdAnimated.toByteArray()));
        hd[hd.length - 1] ^= 1;
        assertThrows(PngValidationException.class, () -> validator.projectImportedCape(hd));
    }

    @Test void baselineAndProgressiveJpegUseOpaqueCanonicalCapePixels() throws Exception {
        BufferedImage source = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                source.setRGB(x, y, x < 22 ? 0xffbf4020 : 0xff303030);
            }
        }
        for (boolean progressive : new boolean[]{false, true}) {
            byte[] jpeg = jpeg(source, progressive);
            var cape = validator.projectCape(jpeg);
            var imported = validator.projectImportedCape(jpeg);
            var decoded = ImageIO.read(new java.io.ByteArrayInputStream(cape.bytes()));
            assertEquals(64, decoded.getWidth());
            assertEquals(32, decoded.getHeight());
            assertEquals(0xff, decoded.getRGB(30, 4) >>> 24);
            assertTrue(cape.hasElytra());
            assertArrayEquals(cape.bytes(), imported.bytes());
            assertEquals(cape.renderSha256(), validator.projectCanonicalCape(cape.bytes()).renderSha256());
            assertThrows(PngValidationException.class, () -> validator.projectCanonicalCape(jpeg));
        }
    }

    @Test void jpegPreflightRejectsWrongSizeMarkersMultipleImagesAndOversize() throws Exception {
        byte[] valid = jpeg(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), false);
        byte[] wrongSize = jpeg(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), false);
        assertEquals(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS,
                assertThrows(PngValidationException.class, () -> validator.projectCape(wrongSize)).reason());
        assertThrows(PngValidationException.class, () -> validator.projectCape(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}));
        assertThrows(PngValidationException.class, () -> validator.projectCape(java.util.Arrays.copyOf(valid, valid.length - 2)));
        byte[] multiple = new byte[valid.length * 2];
        System.arraycopy(valid, 0, multiple, 0, valid.length);
        System.arraycopy(valid, 0, multiple, valid.length, valid.length);
        assertThrows(PngValidationException.class, () -> validator.projectCape(multiple));
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThrows(PngValidationException.class, () -> validator.projectCape(trailing));
        int scanStart = -1;
        for (int i = 2; i < valid.length - 4; i++) {
            if ((valid[i] & 0xff) == 0xff && (valid[i + 1] & 0xff) == 0xda) {
                scanStart = i + 2 + ((valid[i + 2] & 0xff) << 8 | valid[i + 3] & 0xff);
                break;
            }
        }
        assertTrue(scanStart > 0);
        byte[] emptyScan = java.util.Arrays.copyOf(valid, scanStart + 2);
        emptyScan[scanStart] = (byte) 0xff;
        emptyScan[scanStart + 1] = (byte) 0xd9;
        assertThrows(PngValidationException.class, () -> validator.projectCape(emptyScan));
        byte[] oversized = java.util.Arrays.copyOf(valid, PngValidator.DEFAULT_MAX_BYTES + 1);
        assertEquals(PngValidationException.Reason.OVERSIZED,
                assertThrows(PngValidationException.class, () -> validator.projectCape(oversized)).reason());
        byte[] unsupportedFrame = valid.clone();
        for (int i = 2; i < unsupportedFrame.length - 1; i++) {
            if ((unsupportedFrame[i] & 0xff) == 0xff && (unsupportedFrame[i + 1] & 0xff) == 0xc0) {
                unsupportedFrame[i + 1] = (byte) 0xc1;
                break;
            }
        }
        assertThrows(PngValidationException.class, () -> validator.projectCape(unsupportedFrame));
    }

    @Test void pngTransparentWingFallbackRemainsUnchanged() throws Exception {
        BufferedImage png = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        png.setRGB(4, 4, 0xff112233);
        var cape = validator.projectCape(png(png));
        assertFalse(cape.hasElytra());
        assertEquals(0, ImageIO.read(new java.io.ByteArrayInputStream(cape.bytes())).getRGB(30, 4));
    }

    private static byte[] jpeg(BufferedImage image, boolean progressive) throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (var stream = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(stream);
                var params = writer.getDefaultWriteParam();
                params.setProgressiveMode(progressive ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
                writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
            }
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] invalidDeflatePng(int width, int height) throws Exception {
        byte[] bytes = png(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB));
        var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN);
        for (int offset = 8; offset < bytes.length;) {
            int length = buffer.getInt(offset);
            if (buffer.getInt(offset + 4) == 0x49444154) {
                bytes[offset + 8] = 0;
                bytes[offset + 9] = 0;
                var crc = new java.util.zip.CRC32();
                crc.update(bytes, offset + 4, length + 4);
                buffer.putInt(offset + 8 + length, (int) crc.getValue());
                return bytes;
            }
            offset += length + 12;
        }
        throw new AssertionError("IDAT missing");
    }
}
