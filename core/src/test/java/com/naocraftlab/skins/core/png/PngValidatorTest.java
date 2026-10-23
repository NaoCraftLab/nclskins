package com.naocraftlab.skins.core.png;

import com.naocraftlab.skins.core.compatibility.SkinConflictReason;
import com.naocraftlab.skins.client.EncodedTextureLimit;
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
import java.util.Random;
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
    void pureByteCodecsDoNotRequireImageIoDiskCache(@TempDir Path directory) throws Exception {
        byte[] legacy = TestPng.create(64, 32);
        byte[] modern = TestPng.create(64, 64);
        java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg));
        java.io.File previousDirectory = ImageIO.getCacheDirectory();
        boolean previousUseCache = ImageIO.getUseCache();
        Path missingCache = Files.createDirectory(directory.resolve("unavailable-cache"));
        ImageIO.setCacheDirectory(missingCache.toFile());
        ImageIO.setUseCache(true);
        Files.delete(missingCache);
        try {
            assertEquals(new PngInfo(64, 64), validator.validate(validator.normalizeSkin(legacy)));
            assertArrayEquals(modern, validator.projectImport(modern).pngBytes());
            assertEquals(new PngInfo(64, 32), validator.validate(JpegCapeDecoder.toPng(jpeg.toByteArray(), validator.maxBytes())));
            assertTrue(new SneakyCapeDecoder().decode(modern).isEmpty());
        } finally {
            ImageIO.setCacheDirectory(previousDirectory);
            ImageIO.setUseCache(previousUseCache);
        }
    }

    @Test
    void fileImportRequiresStandardDimensionsWhileOtherImportsKeepHdSupport(@TempDir Path directory) throws Exception {
        for (int height : new int[]{32, 64}) {
            Path path = Files.write(directory.resolve("valid.png"), TestPng.create(64, height));
            assertEquals(64, validator.validate(new PngFileReader(validator).projectStandardImport(path).pngBytes()).height());
        }
        for (int[] size : new int[][]{{32, 32}, {128, 64}, {128, 128}}) {
            Path path = Files.write(directory.resolve("invalid.png"), TestPng.create(size[0], size[1]));
            assertEquals(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS,
                    assertThrows(PngValidationException.class, () -> new PngFileReader(validator).projectStandardImport(path)).reason());
        }
        assertEquals(64, validator.validate(validator.projectImport(TestPng.create(128, 128)).pngBytes()).width());
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
    void encodedTextureLimitIsInclusiveAndCheckedBeforeDecode() {
        assertEquals(1_140_850_688, EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES);
        PngValidator boundary = new PngValidator(128);
        byte[] exact = new byte[128];
        assertEquals(PngValidationException.Reason.BAD_SIGNATURE,
                assertThrows(PngValidationException.class,
                        () -> boundary.projectImport(exact)).reason());
        byte[] over = Arrays.copyOf(exact, exact.length + 1);
        assertEquals(PngValidationException.Reason.OVERSIZED,
                assertThrows(PngValidationException.class,
                        () -> boundary.projectImport(over)).reason());
    }

    @Test
    void pathAndStreamProjectionPreserveCanonicalSkinBytesAndHash(@TempDir Path directory) throws Exception {
        byte[] source = insertBeforeIend(TestPng.create(64, 64), "tEXt",
                "Label\0synthetic".getBytes(StandardCharsets.US_ASCII));
        Path path = Files.write(directory.resolve("skin.png"), source);
        assertArrayEquals(source, new PngFileReader(validator).projectImport(path).pngBytes());
        assertArrayEquals(source, new PngFileReader(validator).projectImport(new ByteArrayInputStream(source)).pngBytes());
        assertEquals(validator.renderSha256(source), new PngFileReader(validator).renderSha256(path));
        assertEquals(validator.rawSkinPixelSha256(source), new PngFileReader(validator).rawSkinPixelSha256(path));
    }

    @Test
    void boundedStreamCountsActualBytesAndDoesNotTrustAvailable() throws Exception {
        byte[] source = TestPng.create(64, 64);
        PngValidator exact = new PngValidator(source.length);
        assertEquals(new PngInfo(64, 64), new PngFileReader(exact).validate(new ByteArrayInputStream(source) {
            @Override public int available() { return 0; }
        }));
        byte[] over = Arrays.copyOf(source, source.length + 1);
        assertEquals(PngValidationException.Reason.OVERSIZED,
                assertThrows(PngValidationException.class, () -> new PngFileReader(exact).validate(new ByteArrayInputStream(over))).reason());
    }

    @Test
    void metadataAndTrailingCompressedBytesStayStructurallyBounded(@TempDir Path directory) throws Exception {
        byte[] source = TestPng.create(64, 64);
        Path metadata = Files.write(directory.resolve("metadata.png"),
                insertBeforeIend(source, "tEXt", new byte[1024 * 1024 + 1]));
        assertEquals(PngValidationException.Reason.MALFORMED_CHUNK,
                assertThrows(PngValidationException.class, () -> new PngFileReader(validator).validate(metadata)).reason());
        byte[] corrupt = source.clone();
        corrupt[corrupt.length - 5] ^= 1;
        Path checksum = Files.write(directory.resolve("checksum.png"), corrupt);
        assertEquals(PngValidationException.Reason.BAD_CHECKSUM,
                assertThrows(PngValidationException.class, () -> new PngFileReader(validator).validate(checksum)).reason());
    }

    @Test
    void streamProjectionUsesTheOpenedFileWhenItsNameIsReplaced(@TempDir Path directory) throws Exception {
        byte[] original = TestPng.create(64, 64);
        Path source = Files.write(directory.resolve("source.png"), original);
        Path replacement = Files.write(directory.resolve("replacement.png"), TestPng.create(32, 32));
        try (var input = new java.io.FilterInputStream(Files.newInputStream(source)) {
            private boolean replaced;
            @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                int count = super.read(bytes, offset, Math.min(length, 16));
                if (!replaced) {
                    Files.move(replacement, source, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    replaced = true;
                }
                return count;
            }
        }) {
            assertArrayEquals(original, new PngFileReader(validator).projectImport(input).pngBytes());
        }
        assertEquals(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS,
                assertThrows(PngValidationException.class, () -> new PngFileReader(validator).projectImport(source)).reason());
    }

    @Test
    void largeEncodedSkinProjectsWithoutRetainingTheEncodedBody(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("large.png");
        byte[] raw = new byte[64 * (1 + 64 * 4)];
        byte[] emptyBlocks = new byte[8190];
        for (int offset = 0; offset < emptyBlocks.length; offset += 5) {
            emptyBlocks[offset + 3] = (byte) 255;
            emptyBlocks[offset + 4] = (byte) 255;
        }
        int blocks = 14_000_000;
        int idatLength = 2 + blocks * 5 + 5 + raw.length + 4;
        var adler = new java.util.zip.Adler32(); adler.update(raw);
        try (var output = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(path)))) {
            output.write(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            output.write(chunk("IHDR", ByteBuffer.allocate(13).putInt(64).putInt(64)
                    .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).array()));
            output.writeInt(idatLength);
            byte[] type = "IDAT".getBytes(StandardCharsets.US_ASCII);
            output.write(type);
            CRC32 crc = new CRC32(); crc.update(type);
            byte[] zlibHeader = {(byte) 0x78, 1}; output.write(zlibHeader); crc.update(zlibHeader);
            int remaining = blocks * 5;
            while (remaining > 0) {
                int count = Math.min(remaining, emptyBlocks.length);
                output.write(emptyBlocks, 0, count); crc.update(emptyBlocks, 0, count); remaining -= count;
            }
            byte[] finalHeader = {1, (byte) raw.length, (byte) (raw.length >>> 8),
                    (byte) ~raw.length, (byte) (~raw.length >>> 8)};
            output.write(finalHeader); crc.update(finalHeader);
            output.write(raw); crc.update(raw);
            byte[] checksum = ByteBuffer.allocate(4).putInt((int) adler.getValue()).array();
            output.write(checksum); crc.update(checksum);
            output.writeInt((int) crc.getValue());
            output.write(chunk("IEND", new byte[0]));
        }
        assertTrue(Files.size(path) > 65 * 1024 * 1024);
        NormalizedSkin skin = new PngFileReader(validator).projectImport(path);
        assertTrue(skin.pngBytes().length < 1024);
        assertEquals(validator.renderSha256(encode(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB))),
                validator.renderSha256(skin.pngBytes()));
    }

    @Test
    void actualUncompressedRgba16Adam7MaximumEncoderFitsTheSharedEnvelope() throws Exception {
        int[] xStart = {0, 4, 0, 2, 0, 1, 0};
        int[] yStart = {0, 0, 4, 0, 2, 0, 1};
        int[] xStep = {8, 8, 4, 4, 2, 2, 1};
        int[] yStep = {8, 8, 8, 4, 4, 2, 2};
        class CountingOutput extends java.io.OutputStream {
            private long bytes;
            @Override public void write(int value) { bytes++; }
            @Override public void write(byte[] bytes, int offset, int length) { this.bytes += length; }
        }
        CountingOutput output = new CountingOutput();
        var compressor = new java.util.zip.Deflater(java.util.zip.Deflater.NO_COMPRESSION);
        long filteredBytes = 0;
        try (DeflaterOutputStream encoded = new DeflaterOutputStream(output, compressor, 8192)) {
            for (int pass = 0; pass < 7; pass++) {
                int width = (2048 - xStart[pass] + xStep[pass] - 1) / xStep[pass];
                int height = (65_536 - yStart[pass] + yStep[pass] - 1) / yStep[pass];
                byte[] row = new byte[1 + width * 8];
                for (int y = 0; y < height; y++) { encoded.write(row); filteredBytes += row.length; }
            }
        } finally { compressor.end(); }
        assertEquals(1_073_864_704L, filteredBytes);
        assertTrue(output.bytes >= filteredBytes);
        long largestPermittedContainer = 8 + 25 + 12 + (4096 - 2L) * 12;
        assertTrue(output.bytes + largestPermittedContainer
                < EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES);
    }

    @Test
    void highEntropyRgba16ScaledSkinFitsSharedEncodedLimit() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Random random = new Random(0x5eed);
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            byte[] row = new byte[1 + 2048 * 8];
            for (int y = 0; y < 2048; y++) {
                random.nextBytes(row);
                row[0] = 0;
                deflater.write(row);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
        byte[] header = ByteBuffer.allocate(13)
                .putInt(2048).putInt(2048).put((byte) 16).put((byte) 6)
                .put((byte) 0).put((byte) 0).put((byte) 0).array();
        png.write(chunk("IHDR", header));
        png.write(chunk("IDAT", compressed.toByteArray()));
        png.write(chunk("IEND", new byte[0]));
        byte[] encoded = png.toByteArray();
        assertTrue(encoded.length > 32 * 1024 * 1024);
        assertTrue(encoded.length <= EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES);
        assertEquals(64, validator.validate(validator.projectImport(encoded).pngBytes()).width());
    }

    @Test
    void pathNormalizationUsesTheSameHardByteLimit(@TempDir Path directory) throws Exception {
        PngValidator bounded = new PngValidator(128);
        Path path = Files.write(directory.resolve("oversized.png"), new byte[129]);

        PngValidationException exception = assertThrows(
                PngValidationException.class,
                () -> new PngFileReader(bounded).normalizeSkin(path));

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
    void importAndStoredRenderKeepSneakyMarkerPayloadAndAlpha() throws Exception {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int[] markers = {0xfffff42f, 0xffffffff, 0xff9c59d1, 0xff292929};
        for (int index = 0; index < markers.length; index++) skin.setRGB(60, 48 + index, markers[index]);
        skin.setRGB(56, 16, 0x40224466);
        skin.setRGB(44, 48, 0x8022aa44);
        byte[] source = encode(skin);
        assertArrayEquals(source, validator.projectImport(source).pngBytes());
        assertArrayEquals(source, validator.projectStoredRender(source).pngBytes());
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(validator.normalizeSkin(source)));
        assertEquals(0x40224466, stored.getRGB(56, 16));
        assertEquals(0x8022aa44, stored.getRGB(44, 48));
        assertTrue(new SneakyCapeDecoder().decode(stored).isPresent());

        BufferedImage legacy = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        legacy.setRGB(56, 16, 0x40224466);
        assertTrue(new SneakyCapeDecoder().decode(validator.normalizeSkin(encode(legacy))).isEmpty());
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
