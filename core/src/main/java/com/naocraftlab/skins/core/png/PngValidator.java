package com.naocraftlab.skins.core.png;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import javax.imageio.ImageIO;


public final class PngValidator {
    public static final int DEFAULT_MAX_BYTES = 1_048_576;

    public static final int MAX_SOURCE_DIMENSION = 2_048;
    public static final long MAX_SOURCE_PIXELS = 4_194_304L;
    private static final int MAX_CHUNKS = 4_096;
    private static final byte[] SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final int maxBytes;

    public PngValidator() {
        this(DEFAULT_MAX_BYTES);
    }

    public PngValidator(int maxBytes) {
        if (maxBytes < 128) {
            throw new IllegalArgumentException("maxBytes is unreasonably small");
        }
        this.maxBytes = maxBytes;
    }

    public int maxBytes() {
        return maxBytes;
    }

    public PngInfo validate(Path path) throws IOException, PngValidationException {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return validate(input);
        }
    }

    public PngInfo validate(InputStream input) throws IOException, PngValidationException {
        Objects.requireNonNull(input, "input");
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw failure(PngValidationException.Reason.OVERSIZED, "PNG exceeds the configured size limit");
        }
        return validate(bytes);
    }

    public PngInfo validate(byte[] bytes) throws PngValidationException {
        return inspect(bytes, false, false).info();
    }


    public byte[] normalizeSkin(byte[] bytes) throws PngValidationException {
        Inspection inspection = inspect(bytes, true, true);
        int sourceWidth = inspection.info().width();
        int sourceHeight = inspection.info().height();
        if (sourceWidth == 64) {
            return inspection.canonicalBytes();
        }

        int scale = sourceWidth / 64;
        int targetHeight = sourceHeight / scale;
        BufferedImage source = inspection.image();
        BufferedImage target = new BufferedImage(64, targetHeight, BufferedImage.TYPE_INT_ARGB);
        for (int targetY = 0; targetY < targetHeight; targetY++) {
            int sourceY = targetY * scale;
            for (int targetX = 0; targetX < 64; targetX++) {
                target.setRGB(targetX, targetY, source.getRGB(targetX * scale, sourceY));
            }
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(target, "png", output)) {
                throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG encoder is unavailable");
            }
            byte[] normalized = output.toByteArray();
            validate(normalized);
            return normalized;
        } catch (IOException | RuntimeException exception) {
            throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG could not be normalized");
        }
    }

    public byte[] normalizeSkin(Path path) throws IOException, PngValidationException {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return normalizeSkin(readBounded(input));
        }
    }

    public byte[] normalizeSkin(InputStream input) throws IOException, PngValidationException {
        return normalizeSkin(readBounded(Objects.requireNonNull(input, "input")));
    }

    private Inspection inspect(
            byte[] bytes,
            boolean allowScaledLayout,
            boolean stripPostIendBytes) throws PngValidationException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw failure(PngValidationException.Reason.EMPTY, "PNG is empty");
        }
        if (bytes.length > maxBytes) {
            throw failure(PngValidationException.Reason.OVERSIZED, "PNG exceeds the configured size limit");
        }
        if (bytes.length < SIGNATURE.length || !Arrays.equals(SIGNATURE, Arrays.copyOf(bytes, SIGNATURE.length))) {
            throw failure(PngValidationException.Reason.BAD_SIGNATURE, "File is not a PNG");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buffer.position(SIGNATURE.length);
        boolean sawHeader = false;
        boolean sawPalette = false;
        boolean sawTransparency = false;
        boolean sawImageData = false;
        boolean sawEnd = false;
        int canonicalLength = -1;
        int width = -1;
        int height = -1;
        int bitDepth = -1;
        int colorType = -1;
        int interlace = -1;
        int paletteEntries = 0;
        int chunkIndex = 0;
        String previousType = null;
        ByteArrayOutputStream decoderPng = new ByteArrayOutputStream(bytes.length);
        decoderPng.writeBytes(SIGNATURE);
        ByteArrayOutputStream compressedImageData = new ByteArrayOutputStream();

        while (buffer.hasRemaining()) {
            if (chunkIndex >= MAX_CHUNKS) {
                throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG contains too many chunks");
            }
            if (buffer.remaining() < 12) {
                throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG contains a truncated chunk");
            }
            long unsignedLength = Integer.toUnsignedLong(buffer.getInt());
            if (unsignedLength > Integer.MAX_VALUE || unsignedLength + 8L > buffer.remaining()) {
                throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG chunk length is invalid");
            }
            int length = (int) unsignedLength;
            byte[] typeBytes = new byte[4];
            buffer.get(typeBytes);
            String type = new String(typeBytes, StandardCharsets.US_ASCII);
            if (!isChunkType(typeBytes)) {
                throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG chunk type is invalid");
            }
            byte[] data = new byte[length];
            buffer.get(data);
            long expectedCrc = Integer.toUnsignedLong(buffer.getInt());
            CRC32 crc = new CRC32();
            crc.update(typeBytes);
            crc.update(data);
            if (crc.getValue() != expectedCrc) {
                throw failure(PngValidationException.Reason.BAD_CHECKSUM, "PNG chunk checksum is invalid");
            }

            if ("IHDR".equals(type)) {
                if (chunkIndex != 0 || sawHeader || length != 13) {
                    throw failure(PngValidationException.Reason.INVALID_HEADER, "PNG header is invalid");
                }
                ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                width = header.getInt();
                height = header.getInt();
                bitDepth = Byte.toUnsignedInt(header.get());
                colorType = Byte.toUnsignedInt(header.get());
                int compression = Byte.toUnsignedInt(header.get());
                int filter = Byte.toUnsignedInt(header.get());
                interlace = Byte.toUnsignedInt(header.get());
                if (!validPngHeader(width, height, bitDepth, colorType, compression, filter, interlace)) {
                    throw failure(PngValidationException.Reason.INVALID_HEADER, "PNG header uses invalid encoding values");
                }
                if (!supportedDimensions(width, height, allowScaledLayout)) {
                    throw failure(
                            PngValidationException.Reason.UNSUPPORTED_DIMENSIONS,
                            allowScaledLayout
                                    ? "Skin must use a bounded 64*n square or legacy 64*n by 32*n layout"
                                    : "Skin must be 64x64 or legacy 64x32");
                }
                sawHeader = true;
                appendChunk(decoderPng, typeBytes, data, expectedCrc);
            } else if ("PLTE".equals(type)) {
                if (!sawHeader || sawPalette || sawImageData || length == 0 || length % 3 != 0
                        || length > 768 || colorType == 0 || colorType == 4) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG palette is invalid");
                }
                paletteEntries = length / 3;
                if (colorType == 3 && paletteEntries > 1 << bitDepth) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG palette is too large");
                }
                sawPalette = true;
                appendChunk(decoderPng, typeBytes, data, expectedCrc);
            } else if ("tRNS".equals(type)) {
                if (!sawHeader || sawTransparency || sawImageData
                        || !validTransparencyLength(colorType, length, sawPalette, paletteEntries)) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG transparency is invalid");
                }
                sawTransparency = true;
                appendChunk(decoderPng, typeBytes, data, expectedCrc);
            } else if ("IDAT".equals(type)) {
                if (!sawHeader || sawEnd || sawImageData && !"IDAT".equals(previousType)
                        || colorType == 3 && !sawPalette) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG image data is out of order");
                }
                sawImageData = true;
                compressedImageData.writeBytes(data);
                appendChunk(decoderPng, typeBytes, data, expectedCrc);
            } else if ("IEND".equals(type)) {
                if (!sawHeader || !sawImageData || sawEnd || length != 0) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG end marker is invalid");
                }
                sawEnd = true;
                canonicalLength = buffer.position();
                appendChunk(decoderPng, typeBytes, data, expectedCrc);
                if (buffer.hasRemaining()) {
                    if (!stripPostIendBytes) {
                        throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG has trailing bytes");
                    }


                    buffer.position(buffer.limit());
                }
            } else if ((typeBytes[0] & 0x20) == 0) {
                throw failure(
                        PngValidationException.Reason.UNKNOWN_CRITICAL_CHUNK,
                        "PNG contains an unknown critical chunk");
            }
            previousType = type;
            chunkIndex++;
        }

        if (!sawHeader || !sawImageData || !sawEnd) {
            throw failure(PngValidationException.Reason.MISSING_IMAGE_DATA, "PNG is incomplete");
        }
        byte[] canonicalBytes = canonicalLength == bytes.length
                ? bytes.clone()
                : Arrays.copyOf(bytes, canonicalLength);
        validateInflatedSize(
                compressedImageData.toByteArray(),
                expectedInflatedBytes(width, height, bitDepth, colorType, interlace));
        BufferedImage image = decode(decoderPng.toByteArray(), width, height);
        return new Inspection(new PngInfo(width, height), image, canonicalBytes);
    }

    private byte[] readBounded(InputStream input) throws IOException, PngValidationException {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw failure(PngValidationException.Reason.OVERSIZED, "PNG exceeds the configured size limit");
        }
        return bytes;
    }

    private static void appendChunk(
            ByteArrayOutputStream output, byte[] type, byte[] data, long crc) {
        output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.length).array());
        output.writeBytes(type);
        output.writeBytes(data);
        output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) crc).array());
    }

    private static boolean validTransparencyLength(
            int colorType, int length, boolean sawPalette, int paletteEntries) {
        return switch (colorType) {
            case 0 -> length == 2;
            case 2 -> length == 6;
            case 3 -> sawPalette && length > 0 && length <= paletteEntries;
            default -> false;
        };
    }

    private static long expectedInflatedBytes(
            int width, int height, int bitDepth, int colorType, int interlace)
            throws PngValidationException {
        int channels = switch (colorType) {
            case 0, 3 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> throw failure(PngValidationException.Reason.INVALID_HEADER, "PNG color type is invalid");
        };
        int bitsPerPixel = Math.multiplyExact(channels, bitDepth);
        if (interlace == 0) {
            return Math.multiplyExact((long) height, 1L + rowBytes(width, bitsPerPixel));
        }
        int[] xStart = {0, 4, 0, 2, 0, 1, 0};
        int[] yStart = {0, 0, 4, 0, 2, 0, 1};
        int[] xStep = {8, 8, 4, 4, 2, 2, 1};
        int[] yStep = {8, 8, 8, 4, 4, 2, 2};
        long total = 0;
        for (int pass = 0; pass < xStart.length; pass++) {
            int passWidth = passSize(width, xStart[pass], xStep[pass]);
            int passHeight = passSize(height, yStart[pass], yStep[pass]);
            if (passWidth > 0 && passHeight > 0) {
                total = Math.addExact(
                        total,
                        Math.multiplyExact((long) passHeight, 1L + rowBytes(passWidth, bitsPerPixel)));
            }
        }
        return total;
    }

    private static long rowBytes(int width, int bitsPerPixel) {
        return ((long) width * bitsPerPixel + 7L) / 8L;
    }

    private static int passSize(int fullSize, int start, int step) {
        return fullSize <= start ? 0 : (fullSize - start + step - 1) / step;
    }

    private static void validateInflatedSize(byte[] compressed, long expected)
            throws PngValidationException {
        Inflater inflater = new Inflater();
        byte[] output = new byte[8_192];
        long total = 0;
        try {
            inflater.setInput(compressed);
            while (!inflater.finished()) {
                int count = inflater.inflate(output);
                if (count > 0) {
                    total += count;
                    if (total > expected) {
                        throw failure(
                                PngValidationException.Reason.DECOMPRESSION_LIMIT,
                                "PNG image data exceeds its declared pixel layout");
                    }
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG image data is incomplete");
                } else {
                    throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG image data made no progress");
                }
            }
            if (total != expected || inflater.getRemaining() != 0) {
                throw failure(
                        PngValidationException.Reason.DECODE_FAILED,
                        "PNG image data does not match its declared pixel layout");
            }
        } catch (DataFormatException invalid) {
            throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG image data is invalid");
        } finally {
            inflater.end();
        }
    }

    private static BufferedImage decode(byte[] bytes, int expectedWidth, int expectedHeight)
            throws PngValidationException {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() != expectedWidth || image.getHeight() != expectedHeight) {
                throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG cannot be decoded");
            }
            image.getRGB(0, 0);
            return image;
        } catch (IOException | RuntimeException exception) {
            throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG cannot be decoded");
        }
    }

    private static boolean supportedDimensions(int width, int height, boolean allowScaledLayout) {
        if (!allowScaledLayout) {
            return width == 64 && (height == 64 || height == 32);
        }
        if (width > MAX_SOURCE_DIMENSION
                || height > MAX_SOURCE_DIMENSION
                || (long) width * height > MAX_SOURCE_PIXELS
                || width % 64 != 0) {
            return false;
        }
        int scale = width / 64;
        return scale >= 1 && (height == 64 * scale || height == 32 * scale);
    }

    private static boolean validPngHeader(
            int width,
            int height,
            int bitDepth,
            int colorType,
            int compression,
            int filter,
            int interlace) {
        if (width <= 0 || height <= 0 || compression != 0 || filter != 0 || (interlace != 0 && interlace != 1)) {
            return false;
        }
        return switch (colorType) {
            case 0 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16;
            case 2, 4, 6 -> bitDepth == 8 || bitDepth == 16;
            case 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            default -> false;
        };
    }

    private static boolean isChunkType(byte[] type) {
        for (byte value : type) {
            int unsigned = Byte.toUnsignedInt(value);
            boolean alphabetic = (unsigned >= 'A' && unsigned <= 'Z') || (unsigned >= 'a' && unsigned <= 'z');
            if (!alphabetic) {
                return false;
            }
        }
        return true;
    }

    private static PngValidationException failure(PngValidationException.Reason reason, String message) {
        return new PngValidationException(reason, message);
    }

    private record Inspection(PngInfo info, BufferedImage image, byte[] canonicalBytes) {
        private Inspection {
            canonicalBytes = canonicalBytes.clone();
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }
    }
}
