package com.naocraftlab.skins.core.png;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

final class PngCapeReader {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final int[][] PASSES = {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8},
            {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};
    private final FileChannel file;
    private final long size;
    private final CapeAssetDecoder.Sink sink;
    private final List<Segment> imageData = new ArrayList<>();
    private int width;
    private int height;
    private int targetWidth;
    private int frameHeight;
    private int frames;
    private int colorType;
    private int depth;
    private int interlace;
    private byte[] palette;
    private byte[] transparency;

    static boolean hasSignature(byte[] bytes) {
        return bytes.length >= 8 && Arrays.equals(SIGNATURE, Arrays.copyOf(bytes, 8));
    }

    static void decode(Path path, CapeAssetDecoder.Sink sink) throws IOException, PngValidationException {
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
            new PngCapeReader(file, sink).decode();
        }
    }

    private PngCapeReader(FileChannel file, CapeAssetDecoder.Sink sink) throws IOException {
        this.file = file;
        this.size = file.size();
        this.sink = sink;
    }

    private void decode() throws IOException, PngValidationException {
        if (size < 45 || size > CapeAssetDecoder.MAX_ENCODED_BYTES) throw invalid();
        byte[] signature = read(0, 8);
        if (!Arrays.equals(signature, SIGNATURE)) throw invalid();
        long position = 8;
        int chunks = 0;
        long metadata = 0;
        boolean header = false;
        boolean data = false;
        boolean dataEnded = false;
        boolean end = false;
        boolean sawTransparency = false;
        while (position < size) {
            if (++chunks > 4096 || size - position < 12) throw invalid();
            byte[] chunkHeader = read(position, 8);
            long length = Integer.toUnsignedLong(ByteBuffer.wrap(chunkHeader).getInt());
            byte[] typeBytes = Arrays.copyOfRange(chunkHeader, 4, 8);
            for (byte value : typeBytes) if (!letter(value)) throw invalid();
            String type = new String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII);
            long start = position + 8;
            long next = Math.addExact(Math.addExact(start, length), 4);
            if (next > size) throw invalid();
            CRC32 crc = new CRC32();
            crc.update(typeBytes);
            byte[] block = new byte[64 * 1024];
            long offset = start;
            long remaining = length;
            while (remaining > 0) {
                int count = (int) Math.min(block.length, remaining);
                readInto(offset, block, count);
                crc.update(block, 0, count);
                offset += count;
                remaining -= count;
            }
            long expected = Integer.toUnsignedLong(ByteBuffer.wrap(read(start + length, 4)).getInt());
            if (crc.getValue() != expected) throw CapeAssetDecoder.invalid(PngValidationException.Reason.BAD_CHECKSUM);
            if (!header && !type.equals("IHDR")) throw invalid();
            if (data && !type.equals("IDAT")) dataEnded = true;
            switch (type) {
                case "IHDR" -> {
                    if (header || length != 13) throw invalid();
                    byte[] fields = read(start, 13);
                    ByteBuffer input = ByteBuffer.wrap(fields).order(ByteOrder.BIG_ENDIAN);
                    width = input.getInt();
                    height = input.getInt();
                    depth = Byte.toUnsignedInt(input.get());
                    colorType = Byte.toUnsignedInt(input.get());
                    int compression = Byte.toUnsignedInt(input.get());
                    int filter = Byte.toUnsignedInt(input.get());
                    interlace = Byte.toUnsignedInt(input.get());
                    if (compression != 0 || filter != 0 || interlace > 1 || !validColorDepth()) throw invalid();
                    geometry();
                    header = true;
                }
                case "PLTE" -> {
                    if (data || palette != null || length < 3 || length > 768 || length % 3 != 0
                            || colorType == 0 || colorType == 4 || colorType == 3 && length / 3 > (1 << depth)) throw invalid();
                    palette = read(start, (int) length);
                }
                case "tRNS" -> {
                    if (data || sawTransparency || colorType == 4 || colorType == 6) throw invalid();
                    if (colorType == 3 && (palette == null || length < 1 || length > palette.length / 3)
                            || colorType == 0 && length != 2 || colorType == 2 && length != 6) throw invalid();
                    transparency = read(start, (int) length);
                    sawTransparency = true;
                }
                case "IDAT" -> {
                    if (dataEnded || colorType == 3 && palette == null) throw invalid();
                    data = true;
                    imageData.add(new Segment(start, length));
                }
                case "IEND" -> {
                    if (!data || length != 0 || next != size) throw invalid();
                    end = true;
                }
                case "acTL", "fcTL", "fdAT" -> throw invalid();
                default -> {
                    if ((typeBytes[0] & 0x20) == 0) {
                        throw CapeAssetDecoder.invalid(PngValidationException.Reason.UNKNOWN_CRITICAL_CHUNK);
                    }
                    metadata = Math.addExact(metadata, length);
                    if (metadata > 1024 * 1024) throw invalid();
                }
            }
            position = next;
            if (end) break;
        }
        if (!header || !data || !end) throw invalid();
        sink.begin(targetWidth, targetWidth / 2, frames, 0);
        inflate();
    }

    private void geometry() throws PngValidationException {
        if (width == 46 && height == 22 || width == 92 && height == 44) {
            targetWidth = width == 46 ? 64 : 128;
            frameHeight = height;
            frames = 1;
        } else {
            if (!PreparedCapeAsset.validFrameDimensions(width, width / 2) || height <= 0
                    || height % (width / 2) != 0) throw CapeAssetDecoder.invalid(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS);
            frames = height / (width / 2);
            if (frames > 64) throw CapeAssetDecoder.invalid(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS);
            targetWidth = width;
            frameHeight = width / 2;
        }
        if (PreparedCapeAsset.backingBytes(targetWidth, targetWidth / 2, frames) > CapeAssetDecoder.MAX_BACKING_BYTES) {
            throw CapeAssetDecoder.invalid(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS);
        }
    }

    private boolean validColorDepth() {
        if (width <= 0 || height <= 0) return false;
        return switch (colorType) {
            case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 2, 4, 6 -> depth == 8 || depth == 16;
            case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
            default -> false;
        };
    }

    private void inflate() throws IOException, PngValidationException {
        Segments source = new Segments(file, imageData);
        Inflater inflater = new Inflater();
        byte[] compressed = new byte[8192];
        try {
            int passes = interlace == 0 ? 1 : 7;
            for (int pass = 0; pass < passes; pass++) {
                int x0 = interlace == 0 ? 0 : PASSES[pass][0];
                int y0 = interlace == 0 ? 0 : PASSES[pass][1];
                int dx = interlace == 0 ? 1 : PASSES[pass][2];
                int dy = interlace == 0 ? 1 : PASSES[pass][3];
                int passWidth = passSize(width, x0, dx);
                int passHeight = passSize(height, y0, dy);
                if (passWidth == 0 || passHeight == 0) continue;
                int channels = switch (colorType) { case 0, 3 -> 1; case 2 -> 3; case 4 -> 2; default -> 4; };
                int bitsPerPixel = channels * depth;
                int bytes = Math.toIntExact(((long) passWidth * bitsPerPixel + 7) / 8);
                int filterBytes = Math.max(1, (bitsPerPixel + 7) / 8);
                byte[] previous = new byte[bytes];
                byte[] current = new byte[bytes];
                byte[] rgba = new byte[passWidth * 4];
                byte[] filter = new byte[1];
                for (int row = 0; row < passHeight; row++) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Cape preparation was interrupted");
                    inflateExact(inflater, source, compressed, filter);
                    inflateExact(inflater, source, compressed, current);
                    unfilter(current, previous, filterBytes, filter[0] & 255);
                    pixels(current, rgba, passWidth);
                    int y = y0 + row * dy;
                    int frame = y / (targetWidth / 2);
                    int frameRow = y % (targetWidth / 2);
                    sink.writeRow(frame, frameRow, x0, dx, rgba, passWidth);
                    byte[] swap = previous;
                    previous = current;
                    current = swap;
                }
            }
            byte[] extra = new byte[1];
            if (inflateSome(inflater, source, compressed, extra) != -1 || !inflater.finished()
                    || inflater.getRemaining() != 0 || source.remaining() != 0) throw invalid();
            for (int frame = 0; frame < frames; frame++) sink.completeFrame(100);
        } finally { inflater.end(); }
    }

    private void pixels(byte[] row, byte[] result, int count) throws PngValidationException {
        for (int x = 0; x < count; x++) {
            int red, green, blue, alpha = 255;
            int sample = 0;
            switch (colorType) {
                case 0 -> {
                    sample = sample(row, x, depth);
                    red = green = blue = gray(sample, depth);
                    if (transparency != null && sample == unsignedShort(transparency, 0)) alpha = 0;
                }
                case 2 -> {
                    int unit = depth / 8;
                    int offset = x * 3 * unit;
                    int r = unit == 2 ? unsignedShort(row, offset) : row[offset] & 255;
                    int g = unit == 2 ? unsignedShort(row, offset + unit) : row[offset + unit] & 255;
                    int b = unit == 2 ? unsignedShort(row, offset + 2 * unit) : row[offset + 2 * unit] & 255;
                    red = scale(r, depth); green = scale(g, depth); blue = scale(b, depth);
                    if (transparency != null && r == unsignedShort(transparency, 0)
                            && g == unsignedShort(transparency, 2) && b == unsignedShort(transparency, 4)) alpha = 0;
                }
                case 3 -> {
                    int index = sample(row, x, depth);
                    if (palette == null || index >= palette.length / 3) throw invalid();
                    red = palette[index * 3] & 255;
                    green = palette[index * 3 + 1] & 255;
                    blue = palette[index * 3 + 2] & 255;
                    if (transparency != null && index < transparency.length) alpha = transparency[index] & 255;
                }
                case 4 -> {
                    int unit = depth / 8;
                    int offset = x * 2 * unit;
                    red = green = blue = gray(unit == 2 ? unsignedShort(row, offset) : row[offset] & 255, depth);
                    alpha = scale(unit == 2 ? unsignedShort(row, offset + unit) : row[offset + unit] & 255, depth);
                }
                case 6 -> {
                    int unit = depth / 8;
                    int offset = x * 4 * unit;
                    red = scale(unit == 2 ? unsignedShort(row, offset) : row[offset] & 255, depth);
                    green = scale(unit == 2 ? unsignedShort(row, offset + unit) : row[offset + unit] & 255, depth);
                    blue = scale(unit == 2 ? unsignedShort(row, offset + 2 * unit) : row[offset + 2 * unit] & 255, depth);
                    alpha = scale(unit == 2 ? unsignedShort(row, offset + 3 * unit) : row[offset + 3 * unit] & 255, depth);
                }
                default -> throw invalid();
            }
            int offset = x * 4;
            result[offset] = (byte) (alpha == 0 ? 0 : red);
            result[offset + 1] = (byte) (alpha == 0 ? 0 : green);
            result[offset + 2] = (byte) (alpha == 0 ? 0 : blue);
            result[offset + 3] = (byte) alpha;
        }
    }

    private static int sample(byte[] row, int index, int depth) {
        if (depth == 16) return unsignedShort(row, index * 2);
        if (depth == 8) return row[index] & 255;
        int bit = index * depth;
        return (row[bit / 8] >>> (8 - depth - bit % 8)) & ((1 << depth) - 1);
    }

    private static int scale(int value, int depth) {
        if (depth == 16) return (value * 255 + 32767) / 65535;
        if (depth == 8) return value;
        return value * 255 / ((1 << depth) - 1);
    }

    private static int gray(int value, int depth) {
        if (depth == 8) return Gray8.VALUES[value];
        if (depth == 16) return Gray16.VALUES[value];
        return scale(value, depth);
    }

    private static int[] grayValues(int type, int size) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(1, 1, type);
        int[] values = new int[size];
        for (int sample = 0; sample < size; sample++) {
            image.getRaster().setSample(0, 0, 0, sample);
            values[sample] = image.getRGB(0, 0) & 255;
        }
        return values;
    }

    private static final class Gray8 {
        private static final int[] VALUES = grayValues(java.awt.image.BufferedImage.TYPE_BYTE_GRAY, 256);
    }

    private static final class Gray16 {
        private static final int[] VALUES = grayValues(java.awt.image.BufferedImage.TYPE_USHORT_GRAY, 65536);
    }

    private static int unsignedShort(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }

    private static void unfilter(byte[] row, byte[] previous, int bpp, int filter) throws PngValidationException {
        if (filter > 4) throw invalid();
        for (int i = 0; i < row.length; i++) {
            int left = i >= bpp ? row[i - bpp] & 255 : 0;
            int up = previous[i] & 255;
            int upperLeft = i >= bpp ? previous[i - bpp] & 255 : 0;
            int prediction = switch (filter) {
                case 0 -> 0;
                case 1 -> left;
                case 2 -> up;
                case 3 -> (left + up) / 2;
                default -> {
                    int p = left + up - upperLeft;
                    int a = Math.abs(p - left), b = Math.abs(p - up), c = Math.abs(p - upperLeft);
                    yield a <= b && a <= c ? left : b <= c ? up : upperLeft;
                }
            };
            row[i] = (byte) ((row[i] & 255) + prediction);
        }
    }

    private static void inflateExact(Inflater inflater, Segments source, byte[] compressed, byte[] output)
            throws IOException, PngValidationException {
        int offset = 0;
        while (offset < output.length) {
            int count = inflateSome(inflater, source, compressed, output, offset, output.length - offset);
            if (count <= 0) throw invalid();
            offset += count;
        }
    }

    private static int inflateSome(Inflater inflater, Segments source, byte[] compressed, byte[] output)
            throws IOException, PngValidationException {
        return inflateSome(inflater, source, compressed, output, 0, output.length);
    }

    private static int inflateSome(Inflater inflater, Segments source, byte[] compressed, byte[] output,
                                   int offset, int length) throws IOException, PngValidationException {
        try {
            while (true) {
                if (inflater.finished()) return -1;
                if (inflater.needsInput()) {
                    int fed = source.read(compressed);
                    if (fed < 0) throw invalid();
                    inflater.setInput(compressed, 0, fed);
                }
                int count = inflater.inflate(output, offset, length);
                if (count > 0) return count;
                if (inflater.finished()) return -1;
                if (inflater.needsDictionary() || !inflater.needsInput()) throw invalid();
            }
        } catch (DataFormatException failure) { throw invalid(); }
    }

    private static int passSize(int full, int start, int step) {
        return full <= start ? 0 : (full - start + step - 1) / step;
    }

    private static boolean letter(byte value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    private byte[] read(long position, int length) throws IOException {
        byte[] output = new byte[length];
        readInto(position, output, length);
        return output;
    }

    private void readInto(long position, byte[] target, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(target, 0, length);
        while (buffer.hasRemaining()) {
            int read = file.read(buffer, position);
            if (read < 0) throw new IOException("PNG changed during read");
            position += read;
        }
    }

    private static PngValidationException invalid() {
        return CapeAssetDecoder.invalid(PngValidationException.Reason.DECODE_FAILED);
    }

    private record Segment(long position, long length) { }

    private static final class Segments {
        private final FileChannel file;
        private final List<Segment> segments;
        private int index;
        private long consumed;
        private long remaining;

        private Segments(FileChannel file, List<Segment> segments) {
            this.file = file;
            this.segments = segments;
            for (Segment segment : segments) remaining = Math.addExact(remaining, segment.length);
        }

        long remaining() { return remaining; }

        int read(byte[] output) throws IOException {
            while (index < segments.size()) {
                Segment segment = segments.get(index);
                if (consumed == segment.length) { index++; consumed = 0; continue; }
                int length = (int) Math.min(output.length, segment.length - consumed);
                ByteBuffer buffer = ByteBuffer.wrap(output, 0, length);
                long position = segment.position + consumed;
                while (buffer.hasRemaining()) {
                    int read = file.read(buffer, position);
                    if (read < 0) throw new IOException("PNG changed during read");
                    position += read;
                }
                consumed += length;
                remaining -= length;
                return length;
            }
            return -1;
        }
    }
}
