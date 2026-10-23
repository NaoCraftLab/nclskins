package com.naocraftlab.skins.core.png;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeAssetDecoderTest {
    @TempDir Path root;
    private final CapeAssetDecoder decoder = new CapeAssetDecoder();

    @Test void pngDimensionsStripsAndStaticHashRemainCanonical() throws Exception {
        assertEquals(512L * 1024 * 1024, PreparedCapeAsset.backingBytes(2048, 1024, 64));
        for (int width : List.of(64, 128, 256, 512, 1024, 2048)) {
            BufferedImage image = new BufferedImage(width, width / 2, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(3, 5, 0x7f123456);
            Path source = root.resolve(width + ".png");
            ImageIO.write(image, "png", source.toFile());
            PreparedCapeAsset asset = decoder.prepare(source, root.resolve(width + ".frames"));
            assertEquals(width, asset.width());
            assertEquals(width / 2, asset.height());
            assertEquals(1, asset.frameCount());
            assertFalse(asset.hasElytra());
            assertEquals(width * (width / 2) * 4, asset.readFrame(0).length);
            assertEquals(asset.visualSha256(), com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(
                    asset.descriptorBytes(), asset.backing()).visualSha256());
            if (width == 64) assertEquals(new PngValidator().projectCape(Files.readAllBytes(source)).renderSha256(),
                    asset.visualSha256());
        }
        for (int count : List.of(2, 10, 64)) {
            BufferedImage strip = new BufferedImage(64, 32 * count, BufferedImage.TYPE_INT_ARGB);
            strip.setRGB(2, (count - 1) * 32 + 1, 0xff123456);
            Path source = root.resolve("strip-" + count + ".png");
            ImageIO.write(strip, "png", source.toFile());
            PreparedCapeAsset asset = decoder.prepare(source, root.resolve("strip-" + count + ".frames"));
            assertEquals(count, asset.frameCount());
            assertEquals(100, asset.delaysMillis().get(count - 1));
            assertEquals(0, asset.readFrame(0)[3]);
            assertEquals((byte) 255, asset.readFrame(count - 1)[((1 * 64 + 2) * 4) + 3]);
        }
        for (int height : List.of(65, 32 * 65)) {
            BufferedImage bad = new BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB);
            Path source = root.resolve("bad-" + height + ".png");
            ImageIO.write(bad, "png", source.toFile());
            Path backing = root.resolve("bad-" + height + ".frames");
            assertThrows(PngValidationException.class, () -> decoder.prepare(source, backing));
            assertFalse(Files.exists(backing));
        }
    }

    @Test void interlacedSixteenBitAndTransparentPixelsUseCanonicalRgba() throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_USHORT_GRAY);
        image.getRaster().setSample(4, 5, 0, 0xabcd);
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        Path source = root.resolve("adam7-16.png");
        try (var output = ImageIO.createImageOutputStream(source.toFile())) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
        } finally { writer.dispose(); }
        PreparedCapeAsset asset = decoder.prepare(source, root.resolve("adam7-16.frames"));
        byte[] frame = asset.readFrame(0);
        int offset = (5 * 64 + 4) * 4;
        int expectedGray = image.getRGB(4, 5) & 255;
        assertEquals((byte) expectedGray, frame[offset]);
        assertEquals((byte) expectedGray, frame[offset + 1]);
        assertEquals((byte) expectedGray, frame[offset + 2]);
        assertEquals((byte) 255, frame[offset + 3]);

        BufferedImage first = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        BufferedImage second = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        first.setRGB(7, 8, 0x00112233);
        second.setRGB(7, 8, 0x00aabbcc);
        PreparedCapeAsset left = preparePng(first, "alpha-left");
        PreparedCapeAsset right = preparePng(second, "alpha-right");
        assertEquals(left.visualSha256(), right.visualSha256());
        assertArrayEquals(left.readFrame(0), right.readFrame(0));
    }

    @Test void grayscaleSamplesMatchPublishedGetRgbIdentity() throws Exception {
        int[] samples = {0, 1, 2, 16, 64, 127, 128, 173, 254, 255};
        for (int depth : List.of(8, 16)) for (boolean interlace : List.of(false, true)) {
            BufferedImage image = new BufferedImage(64, 32,
                    depth == 8 ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_USHORT_GRAY);
            for (int i = 0; i < samples.length; i++) {
                int sample = depth == 8 ? samples[i] : samples[i] * 257;
                image.getRaster().setSample(2 + i, 3 + i, 0, sample);
            }
            String name = "gray-" + depth + "-" + interlace;
            Path source = root.resolve(name + ".png");
            writePng(image, source, interlace);
            PreparedCapeAsset asset = decoder.prepare(source, root.resolve(name + ".frames"));
            assertEquals(new PngValidator().projectCape(Files.readAllBytes(source)).renderSha256(),
                    asset.visualSha256(), name);
            BufferedImage actualLegacy = ImageIO.read(source.toFile());
            byte[] frame = asset.readFrame(0);
            for (int i = 0; i < samples.length; i++) {
                int x = 2 + i, y = 3 + i;
                assertEquals(Integer.rotateLeft(actualLegacy.getRGB(x, y), 8), rgba(frame, 64, x, y), name);
            }
        }
    }

    @Test void sixteenBitRgbAndRgbaBoundariesMatchPublishedGetRgbIdentity() throws Exception {
        int[] samples = {1, 0xff, 0x100, 0x7fff, 0x8000, 0xff00, 0xffff};
        for (boolean alpha : List.of(false, true)) {
            int channels = alpha ? 4 : 3;
            var model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), alpha, false,
                    alpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
            var raster = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, 64, 32, channels, null);
            BufferedImage image = new BufferedImage(model, raster, false, null);
            for (int i = 0; i < samples.length; i++) {
                int x = i + 2, y = i + 4;
                image.getRaster().setSample(x, y, 0, samples[i]);
                image.getRaster().setSample(x, y, 1, samples[samples.length - 1 - i]);
                image.getRaster().setSample(x, y, 2, 0x7fff);
                if (alpha) image.getRaster().setSample(x, y, 3, 0xffff - i * 0x1000);
            }
            String name = alpha ? "rgba16" : "rgb16";
            Path source = root.resolve(name + ".png");
            writePng(image, source, true);
            PreparedCapeAsset asset = decoder.prepare(source, root.resolve(name + ".frames"));
            assertEquals(new PngValidator().projectCape(Files.readAllBytes(source)).renderSha256(),
                    asset.visualSha256(), name);
            BufferedImage legacy = ImageIO.read(source.toFile());
            byte[] frame = asset.readFrame(0);
            for (int i = 0; i < samples.length; i++) {
                int x = i + 2, y = i + 4;
                assertEquals(Integer.rotateLeft(legacy.getRGB(x, y), 8), rgba(frame, 64, x, y), name);
            }
        }
    }

    @Test void packedGrayscaleDepthsKeepPublishedIdentity() throws Exception {
        for (int depth : List.of(1, 2, 4)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            ByteBuffer header = ByteBuffer.allocate(13);
            header.putInt(64).putInt(32).put((byte) depth).put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
            writeChunk(output, "IHDR", header.array());
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            int mask = (1 << depth) - 1;
            for (int y = 0; y < 32; y++) {
                raw.write(0);
                for (int byteIndex = 0; byteIndex < 64 * depth / 8; byteIndex++) {
                    int packed = 0;
                    for (int part = 0; part < 8 / depth; part++) {
                        int x = byteIndex * 8 / depth + part;
                        packed |= ((x + y) & mask) << (8 - (part + 1) * depth);
                    }
                    raw.write(packed);
                }
            }
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (var deflater = new java.util.zip.DeflaterOutputStream(compressed)) {
                deflater.write(raw.toByteArray());
            }
            writeChunk(output, "IDAT", compressed.toByteArray());
            writeChunk(output, "IEND", new byte[0]);
            Path source = root.resolve("packed-gray-" + depth + ".png");
            Files.write(source, output.toByteArray());
            PreparedCapeAsset asset = decoder.prepare(source, root.resolve("packed-gray-" + depth + ".frames"));
            assertEquals(new PngValidator().projectCape(output.toByteArray()).renderSha256(),
                    asset.visualSha256(), "depth " + depth);
        }
    }

    @Test void emptyConsecutiveIdatChunksKeepLegacyPngIdentity() throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(25, 1, 0xff7392b4);
        Path original = root.resolve("idat-original.png");
        ImageIO.write(image, "png", original.toFile());
        byte[] source = Files.readAllBytes(original);
        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
        rebuilt.write(source, 0, 8);
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int position = 8;
        while (position < source.length) {
            int length = ByteBuffer.wrap(source, position, 4).getInt();
            int type = ByteBuffer.wrap(source, position + 4, 4).getInt();
            if (type == 0x49444154) idat.write(source, position + 8, length);
            else if (type == 0x49454e44) {
                byte[] compressed = idat.toByteArray();
                int mid = compressed.length / 2;
                writeChunk(rebuilt, "IDAT", new byte[0]);
                writeChunk(rebuilt, "IDAT", Arrays.copyOfRange(compressed, 0, mid));
                writeChunk(rebuilt, "IDAT", new byte[0]);
                writeChunk(rebuilt, "IDAT", Arrays.copyOfRange(compressed, mid, compressed.length));
                writeChunk(rebuilt, "IDAT", new byte[0]);
                rebuilt.write(source, position, length + 12);
            } else rebuilt.write(source, position, length + 12);
            position += length + 12;
        }
        Path split = root.resolve("idat-split.png");
        Files.write(split, rebuilt.toByteArray());
        PreparedCapeAsset asset = decoder.prepare(split, root.resolve("idat-split.frames"));
        assertEquals(new PngValidator().projectCape(rebuilt.toByteArray()).renderSha256(), asset.visualSha256());
    }

    @Test void nonzeroAdam7PixelsAcrossAllPassesAndFramesScatterIntoCorrectRows() throws Exception {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        int[][] passCoordinates = {{0, 0}, {4, 0}, {0, 4}, {2, 0}, {0, 2}, {1, 0}, {0, 1}};
        for (int frame = 0; frame < 2; frame++) for (int pass = 0; pass < 7; pass++) {
            image.setRGB(passCoordinates[pass][0] + 16, passCoordinates[pass][1] + 16 + frame * 64,
                    0xff000000 | (frame + 1) << 16 | (pass + 1) << 8 | 0x5a);
        }
        Path source = root.resolve("adam7-nonzero-strip.png");
        writePng(image, source, true);
        PreparedCapeAsset asset = decoder.prepare(source, root.resolve("adam7-nonzero-strip.frames"));
        assertEquals(2, asset.frameCount());
        for (int frame = 0; frame < 2; frame++) {
            byte[] pixels = asset.readFrame(frame);
            for (int y = 0; y < 64; y++) for (int x = 0; x < 128; x++) {
                assertEquals(Integer.rotateLeft(image.getRGB(x, y + frame * 64), 8),
                        rgba(pixels, 128, x, y), "frame=" + frame + " x=" + x + " y=" + y);
            }
        }
    }

    @Test void everySupportedPngAlphaEncodingSurvivesHdStaticAndAnimatedAdam7() throws Exception {
        int[][] encodings = {{0, 1}, {0, 2}, {0, 4}, {0, 8}, {0, 16}, {2, 8}, {2, 16},
                {3, 1}, {3, 2}, {3, 4}, {3, 8}, {4, 8}, {4, 16}, {6, 8}, {6, 16}};
        for (int[] encoding : encodings) for (boolean interlace : List.of(false, true)) {
            for (int frames : List.of(1, 2)) {
                int type = encoding[0], depth = encoding[1];
                String name = "alpha-" + type + "-" + depth + "-" + interlace + "-" + frames;
                Path source = root.resolve(name + ".png");
                Files.write(source, alphaPng(type, depth, interlace, frames));
                PreparedCapeAsset asset = decoder.prepare(source, root.resolve(name + ".frames"));
                assertEquals(128, asset.width(), name);
                assertEquals(64, asset.height(), name);
                assertEquals(frames, asset.frameCount(), name);
                for (int frame = 0; frame < frames; frame++) {
                    byte[] pixels = asset.readFrame(frame);
                    for (int y = 0; y < 64; y++) for (int x = 0; x < 128; x++) {
                        int index = (x + 3 * (y + 64 * frame)) % (depth == 1 ? 2 : 4);
                        int alpha = type == 0 || type == 2 ? (index == 0 ? 0 : 255) : index * 85;
                        int offset = (y * 128 + x) * 4;
                        assertEquals(alpha, pixels[offset + 3] & 255, name);
                        if (alpha == 0) assertEquals(0, rgba(pixels, 128, x, y), name);
                    }
                }
            }
        }
    }

    private static byte[] alphaPng(int type, int depth, boolean interlace, int frames) throws Exception {
        int width = 128, height = 64 * frames;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        writeChunk(output, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) depth).put((byte) type).put((byte) 0).put((byte) 0)
                .put((byte) (interlace ? 1 : 0)).array());
        if (type == 3) {
            int entries = depth == 1 ? 2 : 4;
            byte[] palette = new byte[entries * 3], alpha = new byte[entries];
            for (int i = 0; i < entries; i++) {
                palette[i * 3] = (byte) (64 + i * 32);
                palette[i * 3 + 1] = (byte) 128;
                palette[i * 3 + 2] = (byte) 192;
                alpha[i] = (byte) (i * 85);
            }
            writeChunk(output, "PLTE", palette);
            writeChunk(output, "tRNS", alpha);
        } else if (type == 0 || type == 2) {
            writeChunk(output, "tRNS", new byte[type == 0 ? 2 : 6]);
        }
        int[] startsX = interlace ? new int[] {0, 4, 0, 2, 0, 1, 0} : new int[] {0};
        int[] startsY = interlace ? new int[] {0, 0, 4, 0, 2, 0, 1} : new int[] {0};
        int[] stepsX = interlace ? new int[] {8, 8, 4, 4, 2, 2, 1} : new int[] {1};
        int[] stepsY = interlace ? new int[] {8, 8, 8, 4, 4, 2, 2} : new int[] {1};
        int channels = switch (type) { case 0, 3 -> 1; case 2 -> 3; case 4 -> 2; default -> 4; };
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int pass = 0; pass < startsX.length; pass++) {
            int columns = (width - startsX[pass] + stepsX[pass] - 1) / stepsX[pass];
            for (int y = startsY[pass]; y < height; y += stepsY[pass]) {
                raw.write(0);
                byte[] row = new byte[(columns * channels * depth + 7) / 8];
                for (int column = 0; column < columns; column++) {
                    int x = startsX[pass] + column * stepsX[pass];
                    int index = (x + 3 * y) % (depth == 1 ? 2 : 4);
                    for (int channel = 0; channel < channels; channel++) {
                        int sample;
                        if (type == 3) sample = index;
                        else if (type == 0 || type == 2) sample = index == 0 ? 0 : (1 << depth) - 1;
                        else if (channel == channels - 1) sample = index * (depth == 16 ? 0x5555 : 85);
                        else sample = depth == 16 ? 0x7392 : 0x73;
                        int bit = (column * channels + channel) * depth;
                        if (depth < 8) row[bit / 8] |= (byte) (sample << (8 - depth - bit % 8));
                        else if (depth == 8) row[bit / 8] = (byte) sample;
                        else { row[bit / 8] = (byte) (sample >>> 8); row[bit / 8 + 1] = (byte) sample; }
                    }
                }
                raw.write(row);
            }
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (var deflater = new java.util.zip.DeflaterOutputStream(compressed)) { deflater.write(raw.toByteArray()); }
        writeChunk(output, "IDAT", compressed.toByteArray());
        writeChunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream output, String type, byte[] data) throws Exception {
        output.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        output.write(name);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        output.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static void writePng(BufferedImage image, Path source, boolean interlace) throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        try (var output = ImageIO.createImageOutputStream(source.toFile())) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (interlace) parameters.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
        } finally { writer.dispose(); }
    }

    @Test void gifCompositionDisposalsTimingLoopsAndLateElytraArePreserved() throws Exception {
        byte[] red = filled(64 * 32, 1);
        byte[] fragment = {2, 0, 0, 2};
        byte[] blue = {3, 3, 3, 3};
        byte[] green = {2};
        List<GifFrame> frames = List.of(new GifFrame(0, 0, 64, 32, red, 1, 3, false),
                new GifFrame(10, 10, 2, 2, fragment, 2, 20, false),
                new GifFrame(11, 10, 2, 2, blue, 3, 0, true),
                new GifFrame(12, 10, 1, 1, green, 1, 0, false));
        Path source = root.resolve("composed.gif");
        Files.write(source, gif(frames, 1));
        PreparedCapeAsset asset = decoder.prepare(source, root.resolve("composed.frames"));
        assertEquals(4, asset.frameCount());
        assertEquals(List.of(30, 200, 100, 100), asset.delaysMillis());
        assertEquals(1, asset.repeatCount());
        assertEquals(0xff0000ff, rgba(asset.readFrame(0), 64, 10, 10));
        assertEquals(0x00ff00ff, rgba(asset.readFrame(1), 64, 10, 10));
        assertEquals(0xff0000ff, rgba(asset.readFrame(1), 64, 11, 10));
        assertEquals(0, rgba(asset.readFrame(2), 64, 10, 10));
        assertEquals(0x0000ffff, rgba(asset.readFrame(2), 64, 11, 10));
        assertEquals(0, rgba(asset.readFrame(3), 64, 11, 10));
        assertEquals(0x00ff00ff, rgba(asset.readFrame(3), 64, 12, 10));
        assertTrue(asset.hasElytra());
        assertEquals(asset.visualSha256(), com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(asset.descriptorBytes(), asset.backing()).visualSha256());

        byte[] wing = filled(64 * 32, 0);
        wing[25] = 1;
        PreparedCapeAsset lateWing = prepareGif(List.of(
                new GifFrame(0, 0, 64, 32, filled(64 * 32, 0), 1, 10, false),
                new GifFrame(0, 0, 64, 32, wing, 1, 10, false)), null, "late-wing");
        assertTrue(lateWing.hasElytra());
    }

    @Test void transparentGifCanvasAndDisposalIgnoreOpaqueBackgroundIndex() throws Exception {
        for (int disposal : List.of(0, 1, 2, 3)) {
            byte[] encoded = gif(List.of(
                    new GifFrame(10, 11, 2, 1, new byte[] {1, 0}, disposal, 10, false),
                    new GifFrame(12, 11, 1, 1, new byte[] {3}, 1, 10, true)), null);
            encoded[11] = 2;
            Path source = root.resolve("transparent-background-" + disposal + ".gif");
            Files.write(source, encoded);
            PreparedCapeAsset asset = decoder.prepare(source, root.resolve("transparent-background-" + disposal + ".frames"));
            byte[] first = asset.readFrame(0), second = asset.readFrame(1);
            assertEquals(0, rgba(first, 64, 0, 0), "initial canvas, disposal=" + disposal);
            assertEquals(0, rgba(first, 64, 11, 11), "transparent image pixel, disposal=" + disposal);
            assertEquals(0xff0000ff, rgba(first, 64, 10, 11));
            assertEquals(disposal <= 1 ? 0xff0000ff : 0, rgba(second, 64, 10, 11),
                    "disposed image rectangle, disposal=" + disposal);
            assertEquals(0, rgba(second, 64, 11, 11));
            assertEquals(0x0000ffff, rgba(second, 64, 12, 11));
        }
    }

    @Test void opaqueGifDisposalRestoresGlobalBackground() throws Exception {
        byte[] encoded = gif(List.of(
                new GifFrame(10, 11, 1, 1, new byte[] {1}, 2, 10, false),
                new GifFrame(12, 11, 1, 1, new byte[] {3}, 1, 10, true)), null);
        encoded[11] = 2;
        for (int i = 0; i < encoded.length - 3; i++) {
            if (encoded[i] == 0x21 && (encoded[i + 1] & 255) == 0xf9 && encoded[i + 2] == 4) {
                encoded[i + 3] &= (byte) ~1;
            }
        }
        Path source = root.resolve("opaque-disposal.gif");
        Files.write(source, encoded);
        PreparedCapeAsset asset = decoder.prepare(source, root.resolve("opaque-disposal.frames"));
        assertEquals(0x00ff00ff, rgba(asset.readFrame(0), 64, 0, 0));
        assertEquals(0xff0000ff, rgba(asset.readFrame(0), 64, 10, 11));
        assertEquals(0x00ff00ff, rgba(asset.readFrame(1), 64, 10, 11));
        assertEquals(0x0000ffff, rgba(asset.readFrame(1), 64, 12, 11));
    }

    @Test void firstGifSubrectUsesOpaqueGlobalBackground() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        shortLE(output, 64); shortLE(output, 32);
        output.write(0xf1); output.write(2); output.write(0);
        output.write(new byte[] {0, 0, 0, (byte) 255, 0, 0, 0, (byte) 255, 0, 0, 0, (byte) 255});
        output.write(0x2c);
        shortLE(output, 10); shortLE(output, 11); shortLE(output, 1); shortLE(output, 1);
        output.write(0); output.write(2);
        byte[] pixels = lzw(new byte[] {1});
        output.write(pixels.length); output.write(pixels); output.write(0); output.write(0x3b);
        Path source = root.resolve("opaque-background.gif");
        Files.write(source, output.toByteArray());
        PreparedCapeAsset asset = decoder.prepare(source, root.resolve("opaque-background.frames"));
        byte[] frame = asset.readFrame(0);
        assertEquals(0x00ff00ff, rgba(frame, 64, 0, 0));
        assertEquals(0xff0000ff, rgba(frame, 64, 10, 11));
    }

    @Test void gifLzwDictionaryGrowthResetAndInterlaceMatchIndependentImageIoWriter() throws Exception {
        byte[] red = new byte[256], green = new byte[256], blue = new byte[256];
        for (int i = 0; i < 256; i++) {
            red[i] = (byte) i;
            green[i] = (byte) (i * 37);
            blue[i] = (byte) (i * 67);
        }
        IndexColorModel palette = new IndexColorModel(8, 256, red, green, blue);
        BufferedImage image = new BufferedImage(512, 256, BufferedImage.TYPE_BYTE_INDEXED, palette);
        Random random = new Random(0x5ca1e);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 512; x++) {
            image.getRaster().setSample(x, y, 0, random.nextInt(256));
        }
        Path source = root.resolve("lzw-interlaced.gif");
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var output = ImageIO.createImageOutputStream(source.toFile())) {
            writer.setOutput(output);
            var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),
                    writer.getDefaultWriteParam());
            String format = "javax_imageio_gif_image_1.0";
            IIOMetadataNode tree = (IIOMetadataNode) metadata.getAsTree(format);
            IIOMetadataNode descriptor = (IIOMetadataNode) tree.getElementsByTagName("ImageDescriptor").item(0);
            descriptor.setAttribute("interlaceFlag", "TRUE");
            metadata.setFromTree(format, tree);
            writer.write(null, new IIOImage(image, null, metadata), writer.getDefaultWriteParam());
        } finally { writer.dispose(); }
        GifCodeStats stats = gifCodeStats(Files.readAllBytes(source));
        assertTrue(stats.interlaced());
        assertEquals(12, stats.maxBits());
        assertTrue(stats.clearCodes() > 1, "The fixture must reset its full dictionary");
        BufferedImage reference = ImageIO.read(source.toFile());
        PreparedCapeAsset asset = decoder.prepare(source, root.resolve("lzw-interlaced.frames"));
        byte[] actual = asset.readFrame(0);
        byte[] expected = new byte[actual.length];
        for (int y = 0; y < 256; y++) for (int x = 0; x < 512; x++) {
            int argb = reference.getRGB(x, y), offset = (y * 512 + x) * 4;
            expected[offset] = (byte) (argb >>> 16);
            expected[offset + 1] = (byte) (argb >>> 8);
            expected[offset + 2] = (byte) argb;
            expected[offset + 3] = (byte) (argb >>> 24);
        }
        assertArrayEquals(expected, actual);
    }

    @Test void jpegStructuralAdmissionMatchesPublishedPathWithoutFullInputAllocation() throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        image.setRGB(25, 2, 0xff102030);
        Path source = root.resolve("structural.jpg");
        ImageIO.write(image, "jpeg", source.toFile());
        byte[] valid = Files.readAllBytes(source);
        PreparedCapeAsset accepted = decoder.prepare(source, root.resolve("structural.frames"));
        assertEquals(new PngValidator().projectCape(valid).renderSha256(), accepted.visualSha256());
        List<byte[]> invalid = List.of(
                Arrays.copyOf(valid, valid.length + 1),
                concat(valid, valid),
                Arrays.copyOf(valid, valid.length - 2));
        for (int i = 0; i < invalid.size(); i++) {
            byte[] candidate = invalid.get(i);
            Path malformed = root.resolve("structural-invalid-" + i + ".jpg");
            Path backing = root.resolve("structural-invalid-" + i + ".frames");
            Files.write(malformed, candidate);
            assertThrows(PngValidationException.class, () -> new PngValidator().projectCape(candidate));
            assertThrows(PngValidationException.class, () -> decoder.prepare(malformed, backing));
            assertFalse(Files.exists(backing));
        }
    }

    @Test void jpegAggregateMetadataExactLimitAndOneByteOverAreEnforced() throws Exception {
        for (boolean progressive : new boolean[] {false, true}) {
            BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
            image.setRGB(25, 2, 0xff102030);
            var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ByteArrayOutputStream original = new ByteArrayOutputStream();
            try (var output = ImageIO.createImageOutputStream(original)) {
                writer.setOutput(output);
                var parameters = writer.getDefaultWriteParam();
                if (progressive) parameters.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
                writer.write(null, new IIOImage(image, null, null), parameters);
            } finally { writer.dispose(); }
            byte[] base = original.toByteArray();
            String name = progressive ? "progressive" : "ordinary";
            Path exact = root.resolve(name + "-metadata-exact.jpg");
            byte[] exactBytes = jpegWithMetadata(base, 1024 * 1024);
            Files.write(exact, exactBytes);
            PngFileReader.preflightJpeg(exact, CapeAssetDecoder.MAX_ENCODED_BYTES);
            PreparedCapeAsset accepted = decoder.prepare(exact, root.resolve(name + "-exact.frames"));
            assertEquals(new PngValidator().projectCape(base).renderSha256(), accepted.visualSha256());
            assertEquals(accepted.visualSha256(), new PngValidator().projectCape(exactBytes).renderSha256());
            assertTrue(accepted.hasElytra());
            Path excessive = root.resolve(name + "-metadata-over.jpg");
            byte[] excessiveBytes = jpegWithMetadata(base, 1024 * 1024 + 1);
            Files.write(excessive, excessiveBytes);
            assertEquals(PngValidationException.Reason.MALFORMED_CHUNK,
                    assertThrows(PngValidationException.class,
                            () -> PngFileReader.preflightJpeg(excessive, CapeAssetDecoder.MAX_ENCODED_BYTES)).reason());
            assertThrows(PngValidationException.class, () -> new PngValidator().projectCape(excessiveBytes));
            Path rejectedBacking = root.resolve(name + "-over.frames");
            assertThrows(PngValidationException.class, () -> decoder.prepare(excessive, rejectedBacking));
            assertFalse(Files.exists(rejectedBacking));
        }
    }

    @Test void gifRejectsBackgroundOutsideGlobalPaletteBeforePublishingBacking() throws Exception {
        byte[] valid = gif(List.of(new GifFrame(10, 11, 1, 1, filled(1, 1), 1, 10, false)), null);
        valid[11] = 2;
        valid[28] &= (byte) ~1;
        Path source = root.resolve("valid-global-background.gif");
        Files.write(source, valid);
        PreparedCapeAsset accepted = decoder.prepare(source, root.resolve("valid-global-background.frames"));
        assertEquals(0x00ff00ff, rgba(accepted.readFrame(0), 64, 0, 0));
        assertEquals(0xff0000ff, rgba(accepted.readFrame(0), 64, 10, 11));
        byte[] invalid = valid.clone(); invalid[11] = 4;
        Path malformed = root.resolve("invalid-global-background.gif");
        Files.write(malformed, invalid);
        Path rejectedBacking = root.resolve("invalid-global-background.frames");
        assertThrows(PngValidationException.class, () -> decoder.prepare(malformed, rejectedBacking));
        assertFalse(Files.exists(rejectedBacking));
        assertEquals(0x00ff00ff, rgba(accepted.readFrame(0), 64, 0, 0));
    }

    private static byte[] jpegWithMetadata(byte[] jpeg, int payloadBytes) throws Exception {
        int existing = 0;
        for (int offset = 2; (jpeg[offset + 1] & 255) != 0xda;) {
            int marker = jpeg[offset + 1] & 255;
            int length = ((jpeg[offset + 2] & 255) << 8) | (jpeg[offset + 3] & 255);
            if (marker == 0xfe || marker >= 0xe0 && marker <= 0xef) existing += length - 2;
            offset += length + 2;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var output = new java.io.DataOutputStream(bytes);
        output.write(jpeg, 0, 2);
        int left = payloadBytes - existing;
        boolean comment = true;
        while (left > 0) {
            int count = Math.min(left, 65_533);
            output.writeByte(255); output.writeByte(comment ? 0xfe : 0xef);
            output.writeShort(count + 2); output.write(new byte[count]);
            left -= count; comment = !comment;
        }
        output.write(jpeg, 2, jpeg.length - 2);
        return bytes.toByteArray();
    }

    @Test void gifPlainTextRequiresTwelveByteHeaderAndConsumesGraphicControl() throws Exception {
        List<GifFrame> frames = List.of(
                new GifFrame(0, 0, 64, 32, filled(64 * 32, 1), 1, 3, false),
                new GifFrame(0, 0, 64, 32, filled(64 * 32, 2), 1, 20, false));
        byte[] original = gif(frames, null);
        int afterFirstControl = 6 + 7 + 12 + 8;
        byte[] malformed = insert(original, afterFirstControl, new byte[] {0x21, 0x01, 0, 0});
        Path malformedSource = root.resolve("plain-text-malformed.gif");
        Path malformedBacking = root.resolve("plain-text-malformed.frames");
        Files.write(malformedSource, malformed);
        assertThrows(PngValidationException.class, () -> decoder.prepare(malformedSource, malformedBacking));
        assertFalse(Files.exists(malformedBacking));

        byte[] plainText = new byte[3 + 12 + 3];
        plainText[0] = 0x21;
        plainText[1] = 0x01;
        plainText[2] = 12;
        plainText[15] = 1;
        plainText[16] = 'x';
        byte[] valid = insert(original, afterFirstControl, plainText);
        Path validSource = root.resolve("plain-text-valid.gif");
        Files.write(validSource, valid);
        PreparedCapeAsset asset = decoder.prepare(validSource, root.resolve("plain-text-valid.frames"));
        assertEquals(List.of(100, 200), asset.delaysMillis());
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] both = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, both, left.length, right.length);
        return both;
    }

    private static byte[] insert(byte[] original, int offset, byte[] inserted) {
        byte[] result = new byte[original.length + inserted.length];
        System.arraycopy(original, 0, result, 0, offset);
        System.arraycopy(inserted, 0, result, offset, inserted.length);
        System.arraycopy(original, offset, result, offset + inserted.length, original.length - offset);
        return result;
    }

    @Test void gifAndPngEquivalentTimelineShareIdentityAndMalformedTailPublishesNothing() throws Exception {
        BufferedImage strip = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) {
            strip.setRGB(x, y, 0xffff0000);
            strip.setRGB(x, y + 32, 0xff00ff00);
        }
        Path png = root.resolve("two.png");
        ImageIO.write(strip, "png", png.toFile());
        PreparedCapeAsset pngAsset = decoder.prepare(png, root.resolve("two.frames"));
        PreparedCapeAsset gifAsset = prepareGif(List.of(
                new GifFrame(0, 0, 64, 32, filled(64 * 32, 1), 1, 10, false),
                new GifFrame(0, 0, 64, 32, filled(64 * 32, 2), 1, 10, false)), null, "two-gif");
        assertEquals(pngAsset.visualSha256(), gifAsset.visualSha256());
        PreparedCapeAsset slower = prepareGif(List.of(
                new GifFrame(0, 0, 64, 32, filled(64 * 32, 1), 1, 10, false),
                new GifFrame(0, 0, 64, 32, filled(64 * 32, 2), 1, 20, false)), null, "slower");
        assertNotEquals(pngAsset.visualSha256(), slower.visualSha256());
        PreparedCapeAsset staticGif = prepareGif(List.of(
                new GifFrame(0, 0, 64, 32, filled(64 * 32, 1), 1, 3, false)), 4, "static");
        BufferedImage red = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) red.setRGB(x, y, 0xffff0000);
        assertEquals(preparePng(red, "red").visualSha256(), staticGif.visualSha256());
        assertEquals(0, staticGif.repeatCount());

        byte[] truncated = gif(List.of(new GifFrame(0, 0, 64, 32, filled(64 * 32, 1), 1, 3, false)), null);
        Path bad = root.resolve("truncated.gif");
        Files.write(bad, Arrays.copyOf(truncated, truncated.length - 2));
        Path backing = root.resolve("truncated.frames");
        assertThrows(PngValidationException.class, () -> decoder.prepare(bad, backing));
        assertFalse(Files.exists(backing));
        byte[] many = gif(java.util.Collections.nCopies(65,
                new GifFrame(0, 0, 64, 32, filled(64 * 32, 1), 1, 10, false)), null);
        Files.write(bad, many);
        assertThrows(PngValidationException.class, () -> decoder.prepare(bad, backing));
        assertFalse(Files.exists(backing));
    }

    @Test void descriptorRejectsCorruptBackingAndMetadata() throws Exception {
        PreparedCapeAsset asset = preparePng(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB), "descriptor");
        byte[] descriptor = asset.descriptorBytes();
        byte[] broken = descriptor.clone();
        broken[8] = 9;
        assertThrows(CapeIntegrityException.class, () -> com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(broken, asset.backing()));
        byte[] falseWing = descriptor.clone();
        falseWing[28] = 1;
        assertThrows(CapeIntegrityException.class, () -> com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(falseWing, asset.backing()));
        Files.write(asset.backing(), new byte[] {1}, java.nio.file.StandardOpenOption.WRITE);
        assertThrows(CapeIntegrityException.class, () -> com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(descriptor, asset.backing()));
    }

    @Test void descriptorAndPngMetadataAndChunkBoundsRejectBeforePublishing() throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Path source = root.resolve("bounded.png");
        ImageIO.write(image, "png", source.toFile());
        byte[] original = Files.readAllBytes(source);
        PreparedCapeAsset accepted = decoder.prepare(source, root.resolve("bounded.frames"));
        assertThrows(java.io.IOException.class, () -> com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(
                Arrays.copyOf(accepted.descriptorBytes(), 64 * 1024 + 1), accepted.backing()));

        ByteArrayOutputStream tooMuchMetadata = new ByteArrayOutputStream();
        tooMuchMetadata.write(original, 0, 33);
        writeChunk(tooMuchMetadata, "tEXt", new byte[1024 * 1024 + 1]);
        tooMuchMetadata.write(original, 33, original.length - 33);
        Path metadata = root.resolve("metadata-limit.png");
        Path metadataBacking = root.resolve("metadata-limit.frames");
        Files.write(metadata, tooMuchMetadata.toByteArray());
        assertThrows(PngValidationException.class, () -> decoder.prepare(metadata, metadataBacking));
        assertFalse(Files.exists(metadataBacking));

        ByteArrayOutputStream tooManyChunks = new ByteArrayOutputStream();
        tooManyChunks.write(original, 0, 33);
        for (int index = 0; index < 4094; index++) writeChunk(tooManyChunks, "tEXt", new byte[0]);
        tooManyChunks.write(original, 33, original.length - 33);
        Path chunks = root.resolve("chunk-limit.png");
        Path chunkBacking = root.resolve("chunk-limit.frames");
        Files.write(chunks, tooManyChunks.toByteArray());
        assertThrows(PngValidationException.class, () -> decoder.prepare(chunks, chunkBacking));
        assertFalse(Files.exists(chunkBacking));
    }

    @Test void gifExtensionMetadataBoundRejectsBeforeBackingPublication() throws Exception {
        byte[] original = gif(List.of(new GifFrame(0, 0, 64, 32,
                filled(64 * 32, 1), 1, 10, false)), null);
        ByteArrayOutputStream comment = new ByteArrayOutputStream();
        comment.write(0x21);
        comment.write(0xfe);
        int remaining = 1024 * 1024 + 1;
        while (remaining > 0) {
            int count = Math.min(255, remaining);
            comment.write(count);
            comment.write(new byte[count]);
            remaining -= count;
        }
        comment.write(0);
        Path source = root.resolve("gif-metadata-limit.gif");
        Path backing = root.resolve("gif-metadata-limit.frames");
        Files.write(source, insert(original, 25, comment.toByteArray()));
        assertThrows(PngValidationException.class, () -> decoder.prepare(source, backing));
        assertFalse(Files.exists(backing));
    }

    @Test void gifImageSubblockCountBoundRejectsAValidPrefixWithExcessTail() throws Exception {
        byte[] original = gif(List.of(new GifFrame(0, 0, 64, 32,
                filled(64 * 32, 1), 1, 10, false)), null);
        Path source = root.resolve("gif-subblock-limit.gif");
        byte[] pairs = new byte[1024 * 1024];
        for (int index = 0; index < pairs.length; index += 2) pairs[index] = 1;
        try (var output = Files.newOutputStream(source)) {
            output.write(original, 0, original.length - 2);
            for (int index = 0; index < 17; index++) output.write(pairs);
            output.write(original, original.length - 2, 2);
        }
        Path backing = root.resolve("gif-subblock-limit.frames");
        assertThrows(PngValidationException.class, () -> decoder.prepare(source, backing));
        assertFalse(Files.exists(backing));
    }

    @Test void exactEncodedCeilingReachesFormatCheckAndPlusOneStopsBeforeBackingCreation() throws Exception {
        Path source = root.resolve("sparse-image.bin");
        try (var channel = java.nio.channels.FileChannel.open(source,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}), CapeAssetDecoder.MAX_ENCODED_BYTES - 1);
        }
        Path backing = root.resolve("sparse.frames");
        assertEquals(PngValidationException.Reason.BAD_SIGNATURE,
                assertThrows(PngValidationException.class, () -> decoder.prepare(source, backing)).reason());
        assertFalse(Files.exists(backing));
        try (var channel = java.nio.channels.FileChannel.open(source, java.nio.file.StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}), CapeAssetDecoder.MAX_ENCODED_BYTES);
        }
        assertEquals(PngValidationException.Reason.OVERSIZED,
                assertThrows(PngValidationException.class, () -> decoder.prepare(source, backing)).reason());
        assertFalse(Files.exists(backing));
    }

    @Test void gifAcceptsAllSixtyFourFramesAndRejectsDamagedLzwAndLatePngCrc() throws Exception {
        GifFrame frame = new GifFrame(0, 0, 64, 32, filled(64 * 32, 1), 1, 10, false);
        PreparedCapeAsset maximum = prepareGif(java.util.Collections.nCopies(64, frame), null, "sixty-four");
        assertEquals(64, maximum.frameCount());
        assertEquals(64L * 64 * 32 * 4, Files.size(maximum.backing()));
        assertArrayEquals(maximum.readFrame(0), maximum.readFrame(63));

        byte[] invalidCodes = gif(List.of(frame), null);
        int dataStart = 6 + 7 + 12 + 8 + 10 + 1 + 1;
        invalidCodes[dataStart] = 0;
        Path invalid = root.resolve("bad-lzw.gif");
        Files.write(invalid, invalidCodes);
        Path backing = root.resolve("bad-lzw.frames");
        assertThrows(PngValidationException.class, () -> decoder.prepare(invalid, backing));
        assertFalse(Files.exists(backing));

        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Path png = root.resolve("late-crc.png");
        ImageIO.write(image, "png", png.toFile());
        byte[] bytes = Files.readAllBytes(png);
        bytes[bytes.length - 1] ^= 1;
        Files.write(png, bytes);
        Path pngBacking = root.resolve("late-crc.frames");
        assertEquals(PngValidationException.Reason.BAD_CHECKSUM,
                assertThrows(PngValidationException.class, () -> decoder.prepare(png, pngBacking)).reason());
        assertFalse(Files.exists(pngBacking));
    }

    @Test void interruptedPreparationDoesNotCreateOrRetainBacking() throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Path source = root.resolve("interrupt.png");
        ImageIO.write(image, "png", source.toFile());
        Path backing = root.resolve("interrupt.frames");
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.IOException.class, () -> decoder.prepare(source, backing));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(backing));
        } finally { Thread.interrupted(); }
        assertEquals(1, decoder.prepare(source, backing).frameCount());
    }

    private PreparedCapeAsset preparePng(BufferedImage image, String name) throws Exception {
        Path source = root.resolve(name + ".png");
        ImageIO.write(image, "png", source.toFile());
        return decoder.prepare(source, root.resolve(name + ".frames"));
    }

    private PreparedCapeAsset prepareGif(List<GifFrame> frames, Integer repeats, String name) throws Exception {
        Path source = root.resolve(name + ".gif");
        Files.write(source, gif(frames, repeats));
        return decoder.prepare(source, root.resolve(name + ".frames"));
    }

    private static byte[] filled(int count, int value) {
        byte[] values = new byte[count];
        Arrays.fill(values, (byte) value);
        return values;
    }

    private static int rgba(byte[] pixels, int width, int x, int y) {
        int offset = (y * width + x) * 4;
        return ((pixels[offset] & 255) << 24) | ((pixels[offset + 1] & 255) << 16)
                | ((pixels[offset + 2] & 255) << 8) | (pixels[offset + 3] & 255);
    }

    private static byte[] gif(List<GifFrame> frames, Integer repeats) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        shortLE(output, 64); shortLE(output, 32);
        output.write(0xf1); output.write(0); output.write(0);
        output.write(new byte[] {0, 0, 0, (byte) 255, 0, 0, 0, (byte) 255, 0, 0, 0, (byte) 255});
        if (repeats != null) {
            output.write(new byte[] {0x21, (byte) 0xff, 11});
            output.write("NETSCAPE2.0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.write(new byte[] {3, 1, (byte) (repeats & 255), (byte) (repeats >>> 8), 0});
        }
        for (GifFrame frame : frames) {
            output.write(new byte[] {0x21, (byte) 0xf9, 4, (byte) ((frame.disposal << 2) | 1)});
            shortLE(output, frame.delay);
            output.write(0); output.write(0);
            output.write(0x2c);
            shortLE(output, frame.x); shortLE(output, frame.y);
            shortLE(output, frame.width); shortLE(output, frame.height);
            if (frame.localPalette) {
                output.write(0x81);
                output.write(new byte[] {0, 0, 0, (byte) 255, 0, 0, 0, (byte) 255, 0, 0, 0, (byte) 255});
            } else output.write(0);
            output.write(2);
            byte[] codes = lzw(frame.indices);
            for (int offset = 0; offset < codes.length; offset += 255) {
                int length = Math.min(255, codes.length - offset);
                output.write(length);
                output.write(codes, offset, length);
            }
            output.write(0);
        }
        output.write(0x3b);
        return output.toByteArray();
    }

    private static byte[] lzw(byte[] indices) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int bits = 0, count = 0;
        List<Integer> codes = new ArrayList<>();
        for (byte index : indices) { codes.add(4); codes.add(index & 255); }
        codes.add(5);
        for (int code : codes) {
            bits |= code << count;
            count += 3;
            while (count >= 8) { output.write(bits & 255); bits >>>= 8; count -= 8; }
        }
        if (count > 0) output.write(bits & 255);
        return output.toByteArray();
    }

    private static GifCodeStats gifCodeStats(byte[] gif) {
        int position = 13;
        int logicalPacked = gif[10] & 255;
        if ((logicalPacked & 0x80) != 0) position += 3 * (1 << ((logicalPacked & 7) + 1));
        while ((gif[position] & 255) == 0x21) {
            position += 2;
            int size;
            do { size = gif[position++] & 255; position += size; } while (size != 0);
        }
        if ((gif[position] & 255) != 0x2c) throw new AssertionError("Expected GIF image");
        int imagePacked = gif[position + 9] & 255;
        boolean interlaced = (imagePacked & 0x40) != 0;
        position += 10;
        if ((imagePacked & 0x80) != 0) position += 3 * (1 << ((imagePacked & 7) + 1));
        int minimum = gif[position++] & 255;
        ByteArrayOutputStream blocks = new ByteArrayOutputStream();
        while (true) {
            int size = gif[position++] & 255;
            if (size == 0) break;
            blocks.write(gif, position, size);
            position += size;
        }
        byte[] codes = blocks.toByteArray();
        int clear = 1 << minimum, end = clear + 1, next = end + 1;
        int bits = minimum + 1, maxBits = bits, clearCodes = 0, bitPosition = 0;
        boolean previous = false;
        while (bitPosition + bits <= codes.length * 8) {
            int code = 0;
            for (int bit = 0; bit < bits; bit++) {
                int at = bitPosition + bit;
                code |= ((codes[at / 8] >>> (at % 8)) & 1) << bit;
            }
            bitPosition += bits;
            if (code == clear) {
                clearCodes++;
                next = end + 1;
                bits = minimum + 1;
                previous = false;
            } else if (code == end) break;
            else {
                if (previous && next < 4096) {
                    next++;
                    if (next == 1 << bits && bits < 12) bits++;
                }
                previous = true;
                maxBits = Math.max(maxBits, bits);
            }
        }
        return new GifCodeStats(interlaced, maxBits, clearCodes);
    }

    private record GifCodeStats(boolean interlaced, int maxBits, int clearCodes) { }

    private static void shortLE(ByteArrayOutputStream output, int value) {
        output.write(value & 255);
        output.write((value >>> 8) & 255);
    }

    private record GifFrame(int x, int y, int width, int height, byte[] indices,
                            int disposal, int delay, boolean localPalette) { }
}
