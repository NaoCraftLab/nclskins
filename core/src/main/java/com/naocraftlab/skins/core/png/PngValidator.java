package com.naocraftlab.skins.core.png;

import com.naocraftlab.skins.client.EncodedTextureLimit;
import com.naocraftlab.skins.core.compatibility.SkinFeatureAnalyzer;
import com.naocraftlab.skins.core.model.SkinVariant;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStreamImpl;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class PngValidator {
    public static final int DEFAULT_MAX_BYTES = EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES;

    public static final int MAX_SOURCE_DIMENSION = 2_048;
    public static final long MAX_SOURCE_PIXELS = 4_194_304L;
    private static final int MAX_CHUNKS = 4_096;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final int LEGACY_MAX_BYTES = 65 * 1024 * 1024;
    private static final byte[] SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final int[][] SLIM_UNUSED_AREAS = {
            {50, 16, 2, 4},
            {54, 20, 2, 12},
            {42, 48, 2, 4},
            {46, 52, 2, 12}
    };

    private final int maxBytes;
    private final SkinFeatureAnalyzer featureAnalyzer = new SkinFeatureAnalyzer();

    public PngValidator() {
        this(DEFAULT_MAX_BYTES);
    }

    public PngValidator(int maxBytes) {
        if (maxBytes < 128) {
            throw new IllegalArgumentException("maxBytes is unreasonably small");
        }
        this.maxBytes = Math.min(maxBytes, EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES);
    }

    public int maxBytes() {
        return maxBytes;
    }

    public PngInfo validate(byte[] bytes) throws PngValidationException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > maxBytes) throw failure(PngValidationException.Reason.OVERSIZED,
                "PNG exceeds the configured size limit");
        try {
            return inspect(new ByteSource(bytes), false, false, null, false).info();
        } catch (IOException exception) {
            throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG could not be read");
        }
    }

    public CapePng projectCape(byte[] bytes) throws PngValidationException {
        return projectCape(bytes, CapeAdmission.SOURCE);
    }

    public CapePng projectImportedCape(byte[] bytes) throws PngValidationException {
        return projectCape(bytes, CapeAdmission.SOURCE);
    }

    public CapePng projectCanonicalCape(byte[] bytes) throws PngValidationException {
        return projectCape(bytes, CapeAdmission.CANONICAL);
    }

    private CapePng projectCape(byte[] bytes, CapeAdmission admission) throws PngValidationException {
        if (admission == CapeAdmission.SOURCE && JpegCapeDecoder.hasSignature(bytes)) {
            return projectCape(JpegCapeDecoder.toPng(bytes, maxBytes), CapeAdmission.CANONICAL);
        }
        Inspection inspection = inspect(bytes, false, false, admission);
        int sourceWidth = inspection.info().width();
        int sourceHeight = inspection.info().height();
        if (!admission.accepts(sourceWidth, sourceHeight)) {
            throw failure(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS, "Unsupported cape dimensions");
        }
        ByteBuffer chunks = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        chunks.position(8);
        while (chunks.remaining() >= 12) {
            int length = chunks.getInt();
            int type = chunks.getInt();
            if (type == 0x6163544c || type == 0x6663544c || type == 0x66644154) {
                throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "Animated cape is unsupported");
            }
            chunks.position(chunks.position() + length + 4);
        }
        BufferedImage image = inspection.image();
        if (sourceWidth == 46 || sourceWidth == 92) {
            int targetWidth = sourceWidth == 46 ? 64 : 128;
            int targetHeight = sourceWidth == 46 ? 32 : 64;
            BufferedImage padded = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
            padded.setRGB(0, 0, sourceWidth, sourceHeight,
                    image.getRGB(0, 0, sourceWidth, sourceHeight, null, 0, sourceWidth), 0, sourceWidth);
            image = padded;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int scale = width / 64;
        boolean elytra = false;
        ByteBuffer pixels = ByteBuffer.allocate(width * height * 4 + (scale == 2 ? 8 : 0))
                .order(ByteOrder.BIG_ENDIAN);
        if (scale == 2) pixels.putInt(width).putInt(height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = visibleArgb(image.getRGB(x, y));
                pixels.putInt(pixel);
                boolean wing = y < 2 * scale
                        ? x >= 24 * scale && x < 44 * scale
                        : y < 22 * scale && x >= 22 * scale && x < 46 * scale;
                elytra |= wing && (pixel >>> 24) != 0;
            }
        }
        try {
            return new CapePng(encode(image),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pixels.array())), elytra);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw failure(PngValidationException.Reason.DECODE_FAILED, "Cape could not be encoded");
        }
    }

    public record CapePng(byte[] bytes, String renderSha256, boolean hasElytra) {
        public CapePng { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    enum CapeAdmission {
        SOURCE, CANONICAL, TEXTURE;

        boolean accepts(int width, int height) {
            return this == TEXTURE && width > 0 && height > 0 && width <= 4096 && height <= 4096
                    && (long) width * height <= 16L * 1024L * 1024L
                    || width == 64 && height == 32
                    || width == 128 && height == 64
                    || this == SOURCE && (width == 46 && height == 22 || width == 92 && height == 44);
        }
    }

    public byte[] normalizeSkin(byte[] bytes) throws PngValidationException {
        return projectImport(bytes).pngBytes();
    }

    public NormalizedSkin normalizeSkinWithVariant(byte[] bytes) throws PngValidationException {
        return projectImport(bytes);
    }

    public NormalizedSkin projectImport(byte[] bytes) throws PngValidationException {
        SkinProjection projection = project(bytes, true);
        return new NormalizedSkin(
                projection.pngBytes(),
                projection.detectedVariant(),
                featureAnalyzer.analyze(projection.image()));
    }

    public StoredSkinProjection projectStoredRender(byte[] bytes)
            throws PngValidationException {
        SkinProjection projection = project(bytes, false);
        return new StoredSkinProjection(
                projection.pngBytes(),
                projection.detectedVariant(),
                featureAnalyzer.analyze(projection.image()));
    }

    private SkinProjection project(byte[] bytes, boolean cleanLegacyImport)
            throws PngValidationException {
        return project(bytes, cleanLegacyImport, true);
    }

    private SkinProjection project(byte[] bytes, boolean cleanLegacyImport, boolean allowScaledLayout)
            throws PngValidationException {
        return project(inspect(bytes, allowScaledLayout, true), cleanLegacyImport);
    }

    private SkinProjection project(Inspection inspection, boolean cleanLegacyImport)
            throws PngValidationException {
        int sourceWidth = inspection.info().width();
        int sourceHeight = inspection.info().height();
        if (sourceWidth == 64 && sourceHeight == 64) {
            return new SkinProjection(
                    inspection.canonicalBytes(),
                    detectSkinVariant(inspection.image()),
                    inspection.image());
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

        SkinVariant variant = targetHeight == 32
                ? SkinVariant.CLASSIC
                : detectSkinVariant(target);
        if (targetHeight == 32) {
            target = expandLegacySkin(target, cleanLegacyImport);
        }

        try {
            byte[] normalized = encode(target);
            validate(normalized);
            return new SkinProjection(normalized, variant, target);
        } catch (IOException | RuntimeException exception) {
            throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG could not be normalized");
        }
    }

    public String rawSkinPixelSha256(byte[] bytes) throws PngValidationException {
        return rawPixelSha256(inspect(bytes, false, false).image());
    }

    public int[] skinPixels(byte[] bytes) throws PngValidationException {
        BufferedImage image = project(inspect(bytes, true, true), false).image();
        return image.getRGB(0, 0, 64, 64, null, 0, 64);
    }

    public String rawCapePixelSha256(byte[] bytes) throws PngValidationException {
        byte[] png = JpegCapeDecoder.hasSignature(bytes) ? JpegCapeDecoder.toPng(bytes, maxBytes) : bytes;
        return rawPixelSha256(inspect(png, false, false, CapeAdmission.SOURCE).image());
    }

    private static String rawPixelSha256(BufferedImage image) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer value = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
            digest.update(value.putInt(image.getWidth()).array());
            value.clear();
            digest.update(value.putInt(image.getHeight()).array());
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    value.clear();
                    digest.update(value.putInt(image.getRGB(x, y)).array());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public String pixelSha256(byte[] bytes) throws PngValidationException {
        return renderSha256(bytes);
    }

    public String renderSha256(byte[] bytes) throws PngValidationException {
        StoredSkinProjection projection = projectStoredRender(bytes);
        Inspection inspection = inspect(projection.pngBytes(), false, false);
        BufferedImage image = inspection.image();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer pixel = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
            digest.update(pixel.putInt(image.getWidth()).array());
            pixel.clear();
            digest.update(pixel.putInt(64).array());
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = visibleArgb(image.getRGB(x, y));
                    pixel.clear();
                    digest.update(pixel.putInt(argb).array());
                }
            }
            new CpmCarrierReader().read(image).appendIdentity(digest, image);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static BufferedImage expandLegacySkin(
            BufferedImage legacy, boolean clearUnusedRegions) {
        BufferedImage expanded = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        expanded.setRGB(
                0, 0, 64, 32,
                legacy.getRGB(0, 0, 64, 32, null, 0, 64),
                0, 64);
        mirrorLegacyLimb(legacy, expanded, 0, 16, 16, 48);
        mirrorLegacyLimb(legacy, expanded, 40, 16, 32, 48);
        applyLegacyOpacitySemantics(expanded);
        if (clearUnusedRegions) {
            clearLegacyUnusedRegions(expanded);
        }
        return expanded;
    }

    static byte[] encode(BufferedImage image) throws IOException, PngValidationException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var encoded = new javax.imageio.stream.MemoryCacheImageOutputStream(output)) {
            if (!ImageIO.write(image, "png", encoded)) {
                throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG encoder is unavailable");
            }
        }
        return output.toByteArray();
    }

    private static void applyLegacyOpacitySemantics(BufferedImage image) {
        setOpaque(image, 0, 0, 32, 16);
        applyLegacyHatTransparency(image, 32, 0, 64, 32);
        setOpaque(image, 0, 16, 64, 32);
        setOpaque(image, 16, 48, 48, 64);
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

    private static void setOpaque(
            BufferedImage image, int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                image.setRGB(x, y, image.getRGB(x, y) | 0xff000000);
            }
        }
    }

    private static void clearLegacyUnusedRegions(BufferedImage image) {
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
                    image.setRGB(x, y, 0);
                }
            }
        }
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

    private static void mirrorLegacyLimb(
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

    private static int visibleArgb(int argb) {
        return (argb >>> 24) == 0 ? 0 : argb;
    }

    NormalizedSkin projectImport(Source source, boolean standard) throws IOException, PngValidationException {
        return normalized(project(inspect(source, !standard, true, null, true), true));
    }

    StoredSkinProjection projectStoredRender(Source source) throws IOException, PngValidationException {
        SkinProjection projection = project(inspect(source, true, true, null, true), false);
        return new StoredSkinProjection(projection.pngBytes(), projection.detectedVariant(),
                featureAnalyzer.analyze(projection.image()));
    }

    String rawSkinPixelSha256(Source source) throws IOException, PngValidationException {
        return rawPixelSha256(inspect(source, false, false, null, false).image());
    }

    CapePng projectCanonicalCape(Source source) throws IOException, PngValidationException {
        return projectCanonicalCape(encode(inspect(source, false, false, CapeAdmission.CANONICAL, false).image()));
    }

    byte[] canonicalTexture(Source source) throws IOException, PngValidationException {
        BufferedImage image = inspect(source, false, false, CapeAdmission.TEXTURE, false).image();
        BufferedImage canonical = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
            canonical.setRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
        }
        return encode(canonical);
    }

    private NormalizedSkin normalized(SkinProjection projection) {
        return new NormalizedSkin(projection.pngBytes(), projection.detectedVariant(),
                featureAnalyzer.analyze(projection.image()));
    }

    private static SkinVariant detectSkinVariant(BufferedImage image) {
        if (image.getWidth() != 64 || image.getHeight() == 32) {
            return SkinVariant.CLASSIC;
        }
        for (int[] area : SLIM_UNUSED_AREAS) {
            for (int y = area[1]; y < area[1] + area[3]; y++) {
                for (int x = area[0]; x < area[0] + area[2]; x++) {
                    if ((image.getRGB(x, y) >>> 24) < 0xff) {
                        return SkinVariant.SLIM;
                    }
                }
            }
        }
        return SkinVariant.CLASSIC;
    }

    private Inspection inspect(
            byte[] bytes,
            boolean allowScaledLayout,
            boolean stripPostIendBytes) throws PngValidationException {
        return inspect(bytes, allowScaledLayout, stripPostIendBytes, null);
    }

    private Inspection inspect(
            byte[] bytes,
            boolean allowScaledLayout,
            boolean stripPostIendBytes,
            CapeAdmission capeAdmission) throws PngValidationException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > maxBytes) throw failure(PngValidationException.Reason.OVERSIZED,
                "PNG exceeds the configured size limit");
        try {
            return inspect(new ByteSource(bytes), allowScaledLayout, stripPostIendBytes, capeAdmission, true);
        } catch (IOException exception) {
            throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG could not be read");
        }
    }

    Inspection inspect(Source source, boolean allowScaledLayout,
            boolean stripPostIendBytes, CapeAdmission capeAdmission, boolean canonicalRequested)
            throws IOException, PngValidationException {
        long size = source.size();
        if (size > maxBytes) throw failure(PngValidationException.Reason.OVERSIZED,
                "PNG exceeds the configured size limit");
        if (size == 0) throw failure(PngValidationException.Reason.EMPTY, "PNG is empty");
        boolean sawHeader = false, sawPalette = false, sawTransparency = false;
        boolean sawImageData = false, sawEnd = false;
        int width = -1, height = -1, bitDepth = -1, colorType = -1, interlace = -1, paletteEntries = 0;
        int metadata = 0, chunkIndex = 0;
        long offset = SIGNATURE.length, canonicalLength = -1;
        String previousType = null;
        List<ImageDataRange> ranges = new ArrayList<>();
        List<PixelChunkRange> pixelChunks = new ArrayList<>();
        pixelChunks.add(new PixelChunkRange(0, SIGNATURE.length, 0));
        long pixelLength = SIGNATURE.length;
        byte[] buffer = new byte[8192];
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(source.stream()))) {
            if (!Arrays.equals(SIGNATURE, input.readNBytes(SIGNATURE.length))) {
                throw failure(PngValidationException.Reason.BAD_SIGNATURE, "File is not a PNG");
            }
            while (offset < size) {
                if (chunkIndex >= MAX_CHUNKS || size - offset < 12) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG chunk is truncated or excessive");
                }
                long unsignedLength = Integer.toUnsignedLong(input.readInt());
                if (unsignedLength > Integer.MAX_VALUE || unsignedLength > size - offset - 12) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG chunk length is invalid");
                }
                int length = (int) unsignedLength;
                byte[] typeBytes = input.readNBytes(4);
                if (!isChunkType(typeBytes)) throw failure(PngValidationException.Reason.MALFORMED_CHUNK,
                        "PNG chunk type is invalid");
                String type = new String(typeBytes, StandardCharsets.US_ASCII);
                if (!"IDAT".equals(type)) {
                    if (length > MAX_METADATA_BYTES - metadata) throw failure(
                            PngValidationException.Reason.MALFORMED_CHUNK, "PNG metadata exceeds its limit");
                    metadata += length;
                }
                CRC32 crc = new CRC32();
                crc.update(typeBytes);
                byte[] header = "IHDR".equals(type) && length == 13 ? new byte[13] : null;
                int remaining = length;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (count < 0) throw new EOFException();
                    if (count == 0) continue;
                    crc.update(buffer, 0, count);
                    if (header != null) System.arraycopy(buffer, 0, header, length - remaining, count);
                    remaining -= count;
                }
                if (crc.getValue() != Integer.toUnsignedLong(input.readInt())) {
                    throw failure(PngValidationException.Reason.BAD_CHECKSUM, "PNG chunk checksum is invalid");
                }
                if ("IHDR".equals(type) || "PLTE".equals(type) || "tRNS".equals(type)
                        || "IDAT".equals(type) || "IEND".equals(type)) {
                    pixelChunks.add(new PixelChunkRange(offset, length + 12, pixelLength));
                    pixelLength += length + 12L;
                }
                if ("IHDR".equals(type)) {
                    if (chunkIndex != 0 || sawHeader || header == null) throw failure(
                            PngValidationException.Reason.INVALID_HEADER, "PNG header is invalid");
                    ByteBuffer values = ByteBuffer.wrap(header);
                    width = values.getInt(); height = values.getInt();
                    bitDepth = Byte.toUnsignedInt(values.get()); colorType = Byte.toUnsignedInt(values.get());
                    int compression = Byte.toUnsignedInt(values.get()), filter = Byte.toUnsignedInt(values.get());
                    interlace = Byte.toUnsignedInt(values.get());
                    if (!validPngHeader(width, height, bitDepth, colorType, compression, filter, interlace)) {
                        throw failure(PngValidationException.Reason.INVALID_HEADER, "PNG header uses invalid encoding values");
                    }
                    if (!(capeAdmission == null ? supportedDimensions(width, height, allowScaledLayout)
                            : capeAdmission.accepts(width, height))) throw failure(
                                    PngValidationException.Reason.UNSUPPORTED_DIMENSIONS, "Unsupported image dimensions");
                    sawHeader = true;
                } else if ("PLTE".equals(type)) {
                    if (!sawHeader || sawPalette || sawImageData || length == 0 || length % 3 != 0
                            || length > 768 || colorType == 0 || colorType == 4) throw failure(
                                    PngValidationException.Reason.MALFORMED_CHUNK, "PNG palette is invalid");
                    paletteEntries = length / 3;
                    if (colorType == 3 && paletteEntries > 1 << bitDepth) throw failure(
                            PngValidationException.Reason.MALFORMED_CHUNK, "PNG palette is too large");
                    sawPalette = true;
                } else if ("tRNS".equals(type)) {
                    if (!sawHeader || sawTransparency || sawImageData
                            || !validTransparencyLength(colorType, length, sawPalette, paletteEntries)) throw failure(
                                    PngValidationException.Reason.MALFORMED_CHUNK, "PNG transparency is invalid");
                    sawTransparency = true;
                } else if ("IDAT".equals(type)) {
                    if (!sawHeader || sawEnd || sawImageData && !"IDAT".equals(previousType)
                            || colorType == 3 && !sawPalette) throw failure(
                                    PngValidationException.Reason.MALFORMED_CHUNK, "PNG image data is out of order");
                    sawImageData = true;
                    ranges.add(new ImageDataRange(offset + 8, length));
                } else if ("IEND".equals(type)) {
                    if (!sawHeader || !sawImageData || sawEnd || length != 0) throw failure(
                            PngValidationException.Reason.MALFORMED_CHUNK, "PNG end marker is invalid");
                    sawEnd = true;
                    canonicalLength = offset + 12;
                    if (canonicalLength < size && !stripPostIendBytes) throw failure(
                            PngValidationException.Reason.MALFORMED_CHUNK, "PNG has trailing bytes");
                    break;
                } else if ((typeBytes[0] & 0x20) == 0) {
                    throw failure(PngValidationException.Reason.UNKNOWN_CRITICAL_CHUNK, "PNG contains an unknown critical chunk");
                } else if (capeAdmission != null && capeAdmission != CapeAdmission.TEXTURE && ("acTL".equals(type) || "fcTL".equals(type) || "fdAT".equals(type))) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "Animated cape is unsupported");
                }
                offset += length + 12L;
                previousType = type;
                chunkIndex++;
            }
        } catch (EOFException exception) {
            throw failure(PngValidationException.Reason.MALFORMED_CHUNK, "PNG is truncated");
        }
        if (!sawHeader || !sawImageData || !sawEnd) throw failure(
                PngValidationException.Reason.MISSING_IMAGE_DATA, "PNG is incomplete");
        validateInflatedSize(source, ranges, expectedInflatedBytes(width, height, bitDepth, colorType, interlace));
        BufferedImage image;
        try (PixelChunkInput input = new PixelChunkInput(source, pixelChunks, pixelLength)) {
            var readers = ImageIO.getImageReadersByFormatName("png");
            if (!readers.hasNext()) throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG decoder is unavailable");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                image = reader.read(0);
                if (image == null || image.getWidth() != width || image.getHeight() != height) throw failure(
                        PngValidationException.Reason.DECODE_FAILED, "PNG cannot be decoded");
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException exception) {
            throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG cannot be decoded");
        }
        byte[] canonical;
        if (!canonicalRequested) {
            canonical = new byte[0];
        } else if (canonicalLength <= LEGACY_MAX_BYTES) {
            try (InputStream input = source.stream()) { canonical = input.readNBytes((int) canonicalLength); }
        } else {
            canonical = encode(image);
        }
        return new Inspection(new PngInfo(width, height), image, canonical);
    }

    BufferedImage decodedSkin(byte[] bytes) throws PngValidationException {
        return inspect(bytes, false, false).image();
    }

    static javax.imageio.stream.ImageInputStream memoryInput(byte[] bytes) {
        return new PixelChunkInput(new ByteSource(bytes),
                List.of(new PixelChunkRange(0, bytes.length, 0)), bytes.length);
    }

    interface Source {
        long size();
        int read(long position, byte[] bytes, int offset, int count) throws IOException;

        default InputStream stream() {
            return new InputStream() {
                private long position;
                @Override public int read() throws IOException {
                    byte[] value = new byte[1];
                    return read(value, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(value[0]);
                }
                @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                    int read = Source.this.read(position, bytes, offset, count);
                    if (read > 0) position += read;
                    return read;
                }
            };
        }
    }

    private record ByteSource(byte[] data) implements Source {
        @Override public long size() { return data.length; }
        @Override public int read(long position, byte[] bytes, int offset, int count) {
            Objects.checkFromIndexSize(offset, count, bytes.length);
            if (count == 0) return 0;
            if (position >= data.length) return -1;
            int read = (int) Math.min(count, data.length - position);
            System.arraycopy(data, Math.toIntExact(position), bytes, offset, read);
            return read;
        }
    }

    private record ImageDataRange(long offset, int length) {}

    private record PixelChunkRange(long offset, int length, long start) {}

    private static final class PixelChunkInput extends ImageInputStreamImpl {
        private final Source source;
        private final List<PixelChunkRange> ranges;
        private final long length;
        private boolean closed;

        private PixelChunkInput(Source source, List<PixelChunkRange> ranges, long length) {
            this.source = source;
            this.ranges = List.copyOf(ranges);
            this.length = length;
        }

        @Override public int read() throws IOException {
            byte[] value = new byte[1];
            return read(value, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(value[0]);
        }

        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            checkClosed();
            Objects.checkFromIndexSize(offset, count, bytes.length);
            bitOffset = 0;
            if (count == 0) return 0;
            if (streamPos >= length) return -1;
            int total = 0;
            while (total < count && streamPos < length) {
                int low = 0, high = ranges.size() - 1;
                while (low < high) {
                    int middle = (low + high + 1) >>> 1;
                    if (ranges.get(middle).start() <= streamPos) low = middle;
                    else high = middle - 1;
                }
                PixelChunkRange range = ranges.get(low);
                int within = (int) (streamPos - range.start());
                int remaining = Math.min(count - total, range.length() - within);
                int read = source.read(range.offset() + within, bytes, offset + total, remaining);
                if (read < 0) throw new EOFException();
                if (read == 0) continue;
                streamPos += read;
                total += read;
            }
            return total;
        }

        @Override public long length() { return length; }

        @Override public void close() throws IOException {
            if (!closed) {
                closed = true;
                super.close();
            }
        }
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

    private static void validateInflatedSize(Source source, List<ImageDataRange> ranges, long expected)
            throws IOException, PngValidationException {
        Inflater inflater = new Inflater();
        byte[] inputBytes = new byte[8192], outputBytes = new byte[8192];
        long total = 0;
        try {
            for (ImageDataRange range : ranges) {
                long position = range.offset();
                int remaining = range.length();
                while (remaining > 0) {
                    int count = source.read(position, inputBytes, 0, Math.min(inputBytes.length, remaining));
                    if (count < 0) throw new EOFException();
                    if (count == 0) continue;
                    remaining -= count;
                    position += count;
                    if (inflater.finished()) throw failure(PngValidationException.Reason.DECODE_FAILED,
                            "PNG image data contains trailing compressed bytes");
                    inflater.setInput(inputBytes, 0, count);
                    while (!inflater.needsInput() && !inflater.finished()) {
                        int produced = inflater.inflate(outputBytes);
                        total += produced;
                        if (total > expected) throw failure(PngValidationException.Reason.DECOMPRESSION_LIMIT,
                                "PNG image data exceeds its declared pixel layout");
                        if (produced == 0 && !inflater.needsInput() && !inflater.finished()) throw failure(
                                PngValidationException.Reason.DECODE_FAILED, "PNG image data made no progress");
                    }
                    if (inflater.finished() && inflater.getRemaining() != 0) throw failure(
                            PngValidationException.Reason.DECODE_FAILED, "PNG image data contains trailing compressed bytes");
                }
            }
            if (!inflater.finished() || total != expected) throw failure(
                    PngValidationException.Reason.DECODE_FAILED, "PNG image data does not match its declared pixel layout");
        } catch (DataFormatException invalid) {
            throw failure(PngValidationException.Reason.DECODE_FAILED, "PNG image data is invalid");
        } finally { inflater.end(); }
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

    private record SkinProjection(
            byte[] pngBytes, SkinVariant detectedVariant, BufferedImage image) {
        private SkinProjection {
            pngBytes = pngBytes.clone();
            Objects.requireNonNull(detectedVariant, "detectedVariant");
            Objects.requireNonNull(image, "image");
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }

    record Inspection(PngInfo info, BufferedImage image, byte[] canonicalBytes) {
        Inspection {
            canonicalBytes = canonicalBytes.clone();
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }
    }
}
