package com.naocraftlab.skins.core.png;

import com.naocraftlab.skins.core.compatibility.SkinConflictReason;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.test.TestPng;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PngValidatorTest {
    private final PngValidator validator = new PngValidator();

    @Test
    void acceptsModernAndLegacySkinDimensions() throws Exception {
        PngInfo modern = validator.validate(TestPng.create(64, 64));
        PngInfo legacy = validator.validate(TestPng.create(64, 32));

        assertEquals(64, modern.width());
        assertEquals(64, modern.height());
        assertFalse(modern.legacyLayout());
        assertTrue(legacy.legacyLayout());
    }

    @Test
    void rejectsOtherDimensions() {
        PngValidationException exception = assertThrows(
                PngValidationException.class,
                () -> validator.validate(TestPng.create(32, 32)));
        assertEquals(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS, exception.reason());
    }

    @Test
    void rejectsBadChunkChecksumAndTruncation() {
        byte[] corrupt = TestPng.create(64, 64);
        corrupt[corrupt.length - 5] ^= 0x01;
        PngValidationException checksum = assertThrows(
                PngValidationException.class,
                () -> validator.validate(corrupt));
        assertEquals(PngValidationException.Reason.BAD_CHECKSUM, checksum.reason());

        byte[] truncated = Arrays.copyOf(TestPng.create(64, 64), 20);
        assertThrows(PngValidationException.class, () -> validator.validate(truncated));
    }

    @Test
    void rejectsOversizedBeforeDecode() {
        PngValidator bounded = new PngValidator(128);
        byte[] bytes = new byte[129];
        PngValidationException exception = assertThrows(
                PngValidationException.class,
                () -> bounded.validate(bytes));
        assertEquals(PngValidationException.Reason.OVERSIZED, exception.reason());
    }

    @Test
    void pathNormalizationUsesTheSameHardByteLimit(@TempDir Path directory) throws Exception {
        PngValidator bounded = new PngValidator(128);
        Path path = Files.write(directory.resolve("oversized.png"), new byte[129]);

        PngValidationException exception = assertThrows(
                PngValidationException.class,
                () -> bounded.normalizeSkin(path));

        assertEquals(PngValidationException.Reason.OVERSIZED, exception.reason());
    }

    @Test
    void rejectsUnknownCriticalChunkBeforeAnyImageDecoder() {
        byte[] source = TestPng.create(64, 64);
        byte[] unknownCritical = insertBeforeIend(source, "ABCD", new byte[] {1});

        PngValidationException exception = assertThrows(
                PngValidationException.class,
                () -> validator.normalizeSkin(unknownCritical));

        assertEquals(PngValidationException.Reason.UNKNOWN_CRITICAL_CHUNK, exception.reason());
    }

    @Test
    void rejectsNonConsecutiveImageDataChunks() {
        byte[] source = TestPng.create(64, 64);
        byte[] split = splitFirstIdatWithAncillaryChunk(source);

        PngValidationException exception = assertThrows(
                PngValidationException.class,
                () -> validator.normalizeSkin(split));

        assertEquals(PngValidationException.Reason.MALFORMED_CHUNK, exception.reason());
    }

    @Test
    void rejectsInflatedImageDataBeyondTheDeclaredPixelLayout() throws Exception {
        byte[] inflated = new byte[64 * (1 + 64 * 4) + 1];
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(inflated);
        }
        byte[] bomb = replaceFirstIdat(TestPng.create(64, 64), compressed.toByteArray());

        PngValidationException exception = assertThrows(
                PngValidationException.class,
                () -> validator.normalizeSkin(bomb));

        assertEquals(PngValidationException.Reason.DECOMPRESSION_LIMIT, exception.reason());
    }

    @Test
    void ancillaryMetadataIsNotRequiredForPixelDecodeAndSourceIdentityIsPreserved()
            throws Exception {
        byte[] source = TestPng.create(64, 64);
        byte[] withMetadata = insertBeforeIend(
                source, "iCCP", new byte[] {'x', 0, 0, 0x78, (byte) 0x9c, 3, 0, 0, 0, 0, 1});

        assertArrayEquals(withMetadata, validator.normalizeSkin(withMetadata));
        assertEquals(validator.pixelSha256(source), validator.pixelSha256(withMetadata));
    }

    @Test
    void renderIdentityPreservesLegacyConsumerPixelsAndSeparatesCleanImport() throws Exception {
        BufferedImage legacy = coordinateImage(64, 32);
        byte[] raw = encode(legacy);
        byte[] clean = validator.projectImport(raw).pngBytes();
        BufferedImage rendererExpanded = expandLegacy(legacy, false);
        StoredSkinProjection stored = validator.projectStoredRender(raw);
        BufferedImage storedImage = ImageIO.read(new ByteArrayInputStream(stored.pngBytes()));
        BufferedImage cleanImage = ImageIO.read(new ByteArrayInputStream(clean));

        assertEquals(
                validator.renderSha256(raw),
                validator.renderSha256(encode(rendererExpanded)));
        assertNotEquals(validator.renderSha256(raw), validator.renderSha256(clean));
        assertEquals(legacy.getRGB(4, 0), storedImage.getRGB(4, 0));
        assertEquals(legacy.getRGB(4, 16), storedImage.getRGB(23, 48));
        assertEquals(0, cleanImage.getRGB(4, 0));
        assertEquals(
                java.util.List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA),
                stored.featureEvidence().potentialConflicts());
        assertEquals(SkinFeatureEvidence.ORDINARY,
                validator.projectImport(raw).featureEvidence());

        rendererExpanded.setRGB(0, 32, 0xff123456);
        assertNotEquals(
                validator.renderSha256(raw),
                validator.renderSha256(encode(rendererExpanded)));
    }

    @Test
    void pixelIdentityIgnoresRgbOfFullyTransparentPixelsOnly() throws Exception {
        BufferedImage first = opaqueSkin(64, 64);
        BufferedImage second = opaqueSkin(64, 64);
        first.setRGB(0, 0, 0x00112233);
        second.setRGB(0, 0, 0x00aabbcc);

        assertEquals(validator.pixelSha256(encode(first)), validator.pixelSha256(encode(second)));

        second.setRGB(0, 0, 0x01aabbcc);
        assertNotEquals(validator.pixelSha256(encode(first)), validator.pixelSha256(encode(second)));
    }

    @Test
    void normalizesScaledModernAndLegacyLayouts() throws Exception {
        byte[] modern = validator.normalizeSkin(TestPng.create(128, 128));
        byte[] legacy = validator.normalizeSkin(TestPng.create(256, 128));

        assertEquals(64, validator.validate(modern).width());
        assertEquals(64, validator.validate(modern).height());
        assertEquals(64, validator.validate(legacy).width());
        assertEquals(64, validator.validate(legacy).height());
    }

    @Test
    void leavesModernApiSizedPngUnchangedAndCleansLegacyLayout() throws Exception {
        byte[] modern = TestPng.create(64, 64);
        byte[] legacy = TestPng.create(64, 32);

        assertArrayEquals(modern, validator.normalizeSkin(modern));
        assertArrayEquals(modern, validator.normalizeSkinWithVariant(modern).pngBytes());
        assertArrayEquals(modern, validator.projectImport(modern).pngBytes());
        assertArrayEquals(modern, validator.projectStoredRender(modern).pngBytes());
        assertFalse(Arrays.equals(legacy, validator.normalizeSkin(legacy)));
        assertEquals(64, validator.validate(validator.normalizeSkin(legacy)).height());
    }

    @Test
    void legacyNormalizationClearsEveryPixelOutsideRenderableUvMask() throws Exception {
        BufferedImage legacy = opaqueSkin(64, 32);
        BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(
                validator.normalizeSkin(encode(legacy))));

        int[][] unused = {
                {0, 0}, {24, 0}, {32, 0}, {56, 0},
                {0, 16}, {12, 16}, {16, 16}, {36, 16}, {40, 16}, {52, 16}, {56, 16},
                {0, 32}, {15, 47}, {48, 48}, {63, 63}
        };
        for (int[] pixel : unused) {
            assertEquals(0, normalized.getRGB(pixel[0], pixel[1]),
                    "unused legacy pixel " + pixel[0] + "," + pixel[1]);
        }
        assertEquals(0xff3186d8, normalized.getRGB(8, 0));
        assertEquals(0xff3186d8, normalized.getRGB(20, 16));
        assertEquals(0xff3186d8, normalized.getRGB(20, 48));
    }

    @Test
    void detectsSlimFromTransparencyInEachUnusedArmArea() throws Exception {
        int[][] areas = {
                {50, 16},
                {54, 20},
                {42, 48},
                {46, 52}
        };
        assertEquals(
                SkinVariant.CLASSIC,
                validator.normalizeSkinWithVariant(encode(opaqueSkin(64, 64))).detectedVariant());

        for (int index = 0; index < areas.length; index++) {
            BufferedImage skin = opaqueSkin(64, 64);
            int alpha = index == 0 ? 0x00 : 0x7f;
            skin.setRGB(areas[index][0], areas[index][1], alpha << 24 | 0x003186d8);

            assertEquals(
                    SkinVariant.SLIM,
                    validator.normalizeSkinWithVariant(encode(skin)).detectedVariant(),
                    "unused arm area " + index);
        }
    }

    @Test
    void ignoresOpaqueMatteAndTransparencyOutsideSlimAreas() throws Exception {
        BufferedImage skin = opaqueSkin(64, 64);
        skin.setRGB(50, 16, 0xff000000);
        skin.setRGB(54, 20, 0xffffffff);
        skin.setRGB(42, 48, 0xff000000);
        skin.setRGB(46, 52, 0xffffffff);
        skin.setRGB(49, 16, 0x003186d8);

        assertEquals(
                SkinVariant.CLASSIC,
                validator.normalizeSkinWithVariant(encode(skin)).detectedVariant());
    }

    @Test
    void treatsLegacyLayoutAsClassicAndDetectsAfterScaledNormalization() throws Exception {
        BufferedImage legacy = opaqueSkin(64, 32);
        legacy.setRGB(54, 20, 0x003186d8);
        assertEquals(
                SkinVariant.CLASSIC,
                validator.normalizeSkinWithVariant(encode(legacy)).detectedVariant());

        BufferedImage scaled = opaqueSkin(128, 128);
        for (int y = 40; y < 42; y++) {
            for (int x = 108; x < 110; x++) {
                scaled.setRGB(x, y, 0x003186d8);
            }
        }
        NormalizedSkin normalized = validator.normalizeSkinWithVariant(encode(scaled));

        assertEquals(SkinVariant.SLIM, normalized.detectedVariant());
        assertEquals(new PngInfo(64, 64), validator.validate(normalized.pngBytes()));
    }

    @Test
    void normalizationStripsMalformedLauncherMetadataAppendedAfterIend() throws Exception {
        byte[] source = TestPng.create(64, 64);
        byte[] launcherCompatible = appendChunk(
                source,
                "tEXt",
                "Model\0Players/Steve".getBytes(StandardCharsets.ISO_8859_1));
        launcherCompatible[launcherCompatible.length - 1] ^= 0x01;

        PngValidationException strictFailure = assertThrows(
                PngValidationException.class,
                () -> validator.validate(launcherCompatible));
        byte[] normalized = validator.normalizeSkin(launcherCompatible);

        assertEquals(PngValidationException.Reason.MALFORMED_CHUNK, strictFailure.reason());
        assertArrayEquals(source, normalized);
        assertEquals(source.length, normalized.length);
        assertEquals(64, validator.validate(normalized).width());
        assertEquals(64, validator.validate(normalized).height());
        BufferedImage originalRaster = ImageIO.read(new ByteArrayInputStream(launcherCompatible));
        BufferedImage normalizedRaster = ImageIO.read(new ByteArrayInputStream(normalized));
        assertArrayEquals(
                originalRaster.getRGB(0, 0, 64, 64, null, 0, 64),
                normalizedRaster.getRGB(0, 0, 64, 64, null, 0, 64));
    }

    @Test
    void strictValidationRejectsButNormalizationSanitizesEveryPostIendSuffix() throws Exception {
        byte[] source = TestPng.create(64, 64);
        byte[] critical = appendChunk(source, "ABCD", new byte[] {1});
        byte[] unframed = Arrays.copyOf(source, source.length + 1);

        PngValidationException criticalFailure = assertThrows(
                PngValidationException.class,
                () -> validator.validate(critical));
        PngValidationException unframedFailure = assertThrows(
                PngValidationException.class,
                () -> validator.validate(unframed));

        assertEquals(PngValidationException.Reason.MALFORMED_CHUNK, criticalFailure.reason());
        assertEquals(PngValidationException.Reason.MALFORMED_CHUNK, unframedFailure.reason());
        assertArrayEquals(source, validator.normalizeSkin(critical));
        assertArrayEquals(source, validator.normalizeSkin(unframed));
    }

    @Test
    void downscalesWithNearestNeighbourSampling() throws Exception {
        BufferedImage source = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int color = 0xff000000 | (x << 16) | (y << 8) | (x ^ y);
                for (int sourceY = y * 2; sourceY < y * 2 + 2; sourceY++) {
                    for (int sourceX = x * 2; sourceX < x * 2 + 2; sourceX++) {
                        source.setRGB(sourceX, sourceY, color);
                    }
                }
            }
        }

        BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(validator.normalizeSkin(encode(source))));

        assertEquals(64, normalized.getWidth());
        assertEquals(64, normalized.getHeight());
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int expected = 0xff000000 | (x << 16) | (y << 8) | (x ^ y);
                assertEquals(expected, normalized.getRGB(x, y), "logical pixel at " + x + "," + y);
            }
        }
    }

    @Test
    void rejectsNonMinecraftScaleAndDimensionsBeforeDecodeAllocation() {
        PngValidationException wrongScale = assertThrows(
                PngValidationException.class,
                () -> validator.normalizeSkin(TestPng.create(96, 96)));
        assertEquals(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS, wrongScale.reason());

        byte[] forgedHugeHeader = withHeaderDimensions(TestPng.create(64, 64), 2_112, 2_112);
        PngValidationException tooLarge = assertThrows(
                PngValidationException.class,
                () -> validator.normalizeSkin(forgedHugeHeader));
        assertEquals(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS, tooLarge.reason());
    }

    @Test
    void normalizationStillRejectsCorruptScaledPng() {
        byte[] corrupt = TestPng.create(128, 128);
        corrupt[corrupt.length - 5] ^= 0x01;

        PngValidationException exception = assertThrows(
                PngValidationException.class,
                () -> validator.normalizeSkin(corrupt));

        assertEquals(PngValidationException.Reason.BAD_CHECKSUM, exception.reason());
    }

    private static byte[] encode(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static BufferedImage opaqueSkin(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xff3186d8);
            }
        }
        return image;
    }

    private static BufferedImage coordinateImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xff000000 | x << 8 | y);
            }
        }
        return image;
    }

    private static BufferedImage expandLegacy(BufferedImage legacy, boolean clearUnusedRegions) {
        BufferedImage expanded = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        expanded.setRGB(0, 0, 64, 32, legacy.getRGB(0, 0, 64, 32, null, 0, 64), 0, 64);
        mirrorLimb(legacy, expanded, 0, 16, 16, 48);
        mirrorLimb(legacy, expanded, 40, 16, 32, 48);
        setOpaque(expanded, 0, 0, 32, 16);
        applyLegacyHatTransparency(expanded, 32, 0, 64, 32);
        setOpaque(expanded, 0, 16, 64, 32);
        setOpaque(expanded, 16, 48, 48, 64);
        if (!clearUnusedRegions) {
            return expanded;
        }
        boolean[][] renderable = new boolean[64][64];
        mark(renderable, 8, 0, 16, 8);
        mark(renderable, 16, 0, 24, 8);
        mark(renderable, 0, 8, 32, 16);
        mark(renderable, 40, 0, 56, 8);
        mark(renderable, 32, 8, 64, 16);
        mark(renderable, 4, 16, 12, 20);
        mark(renderable, 0, 20, 16, 32);
        mark(renderable, 20, 16, 36, 20);
        mark(renderable, 16, 20, 40, 32);
        mark(renderable, 44, 16, 52, 20);
        mark(renderable, 40, 20, 56, 32);
        mark(renderable, 20, 48, 28, 52);
        mark(renderable, 16, 52, 32, 64);
        mark(renderable, 36, 48, 44, 52);
        mark(renderable, 32, 52, 48, 64);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                if (!renderable[y][x]) {
                    expanded.setRGB(x, y, 0);
                }
            }
        }
        return expanded;
    }

    private static void mark(
            boolean[][] mask, int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                mask[y][x] = true;
            }
        }
    }

    private static void clear(BufferedImage image, int x, int y, int width, int height) {
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                image.setRGB(column, row, 0);
            }
        }
    }

    private static void setOpaque(
            BufferedImage image, int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                image.setRGB(x, y, image.getRGB(x, y) | 0xff000000);
            }
        }
    }

    private static void applyLegacyHatTransparency(
            BufferedImage image, int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if ((image.getRGB(x, y) >>> 24) < 128) {
                    return;
                }
            }
        }
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                image.setRGB(x, y, image.getRGB(x, y) & 0x00ffffff);
            }
        }
    }

    private static void mirrorLimb(
            BufferedImage source,
            BufferedImage target,
            int sourceX,
            int sourceY,
            int targetX,
            int targetY) {
        copyMirrored(source, target, sourceX + 4, sourceY, 4, 4, targetX + 4, targetY);
        copyMirrored(source, target, sourceX + 8, sourceY, 4, 4, targetX + 8, targetY);
        copyMirrored(source, target, sourceX, sourceY + 4, 4, 12, targetX + 8, targetY + 4);
        copyMirrored(source, target, sourceX + 4, sourceY + 4, 4, 12, targetX + 4, targetY + 4);
        copyMirrored(source, target, sourceX + 8, sourceY + 4, 4, 12, targetX, targetY + 4);
        copyMirrored(source, target, sourceX + 12, sourceY + 4, 4, 12, targetX + 12, targetY + 4);
    }

    private static void copyMirrored(
            BufferedImage source,
            BufferedImage target,
            int sourceX,
            int sourceY,
            int width,
            int height,
            int targetX,
            int targetY) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                target.setRGB(
                        targetX + width - 1 - x,
                        targetY + y,
                        source.getRGB(sourceX + x, sourceY + y));
            }
        }
    }

    private static byte[] withHeaderDimensions(byte[] source, int width, int height) {
        byte[] result = source.clone();
        ByteBuffer.wrap(result).putInt(16, width).putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(result, 12, 17);
        ByteBuffer.wrap(result).putInt(29, (int) crc.getValue());
        return result;
    }

    private static byte[] appendChunk(byte[] source, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length);
        chunk.putInt(data.length);
        chunk.put(typeBytes);
        chunk.put(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        chunk.putInt((int) crc.getValue());

        byte[] result = Arrays.copyOf(source, source.length + chunk.capacity());
        System.arraycopy(chunk.array(), 0, result, source.length, chunk.capacity());
        return result;
    }

    private static byte[] insertBeforeIend(byte[] source, String type, byte[] data) {
        int iend = source.length - 12;
        byte[] chunk = chunk(type, data);
        byte[] result = new byte[source.length + chunk.length];
        System.arraycopy(source, 0, result, 0, iend);
        System.arraycopy(chunk, 0, result, iend, chunk.length);
        System.arraycopy(source, iend, result, iend + chunk.length, source.length - iend);
        return result;
    }

    private static byte[] splitFirstIdatWithAncillaryChunk(byte[] source) {
        int offset = 8;
        while (offset < source.length) {
            int length = ByteBuffer.wrap(source, offset, 4).getInt();
            String type = new String(source, offset + 4, 4, StandardCharsets.US_ASCII);
            if ("IDAT".equals(type)) {
                byte[] data = Arrays.copyOfRange(source, offset + 8, offset + 8 + length);
                int split = Math.max(1, data.length / 2);
                byte[] first = chunk("IDAT", Arrays.copyOfRange(data, 0, split));
                byte[] metadata = chunk("tEXt", "ignored\0value".getBytes(StandardCharsets.ISO_8859_1));
                byte[] second = chunk("IDAT", Arrays.copyOfRange(data, split, data.length));
                int oldChunkLength = length + 12;
                byte[] result = new byte[source.length - oldChunkLength
                        + first.length + metadata.length + second.length];
                System.arraycopy(source, 0, result, 0, offset);
                int cursor = offset;
                for (byte[] replacement : new byte[][] {first, metadata, second}) {
                    System.arraycopy(replacement, 0, result, cursor, replacement.length);
                    cursor += replacement.length;
                }
                System.arraycopy(
                        source,
                        offset + oldChunkLength,
                        result,
                        cursor,
                        source.length - offset - oldChunkLength);
                return result;
            }
            offset += length + 12;
        }
        throw new AssertionError("test PNG has no IDAT chunk");
    }

    private static byte[] replaceFirstIdat(byte[] source, byte[] replacementData) {
        int offset = 8;
        while (offset < source.length) {
            int length = ByteBuffer.wrap(source, offset, 4).getInt();
            String type = new String(source, offset + 4, 4, StandardCharsets.US_ASCII);
            if ("IDAT".equals(type)) {
                byte[] replacement = chunk("IDAT", replacementData);
                byte[] result = new byte[source.length - length - 12 + replacement.length];
                System.arraycopy(source, 0, result, 0, offset);
                System.arraycopy(replacement, 0, result, offset, replacement.length);
                System.arraycopy(
                        source,
                        offset + length + 12,
                        result,
                        offset + replacement.length,
                        source.length - offset - length - 12);
                return result;
            }
            offset += length + 12;
        }
        throw new AssertionError("test PNG has no IDAT chunk");
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length);
        chunk.putInt(data.length);
        chunk.put(typeBytes);
        chunk.put(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        chunk.putInt((int) crc.getValue());
        return chunk.array();
    }
}
