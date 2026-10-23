package com.naocraftlab.skins.core.png;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class GifCapeReader {
    private final DataInputStream input;
    private final CapeAssetDecoder.Sink sink;
    private int width;
    private int height;
    private byte[] globalPalette;
    private int background;
    private int backgroundIndex;
    private long metadata;
    private long subblocks;
    private int repeatCount;
    private boolean loopSeen;
    private Control control = Control.DEFAULT;
    private boolean controlPending;
    private int previousDisposal;
    private int previousX;
    private int previousY;
    private int previousWidth;
    private int previousHeight;
    private int previousTransparentIndex = -1;
    private byte[] previousSnapshot;
    private byte[] canvas;

    static boolean hasSignature(byte[] signature) {
        if (signature.length < 6) return false;
        return signature[0] == 'G' && signature[1] == 'I' && signature[2] == 'F'
                && signature[3] == '8' && (signature[4] == '7' || signature[4] == '9')
                && signature[5] == 'a';
    }

    static void decode(Path path, CapeAssetDecoder.Sink sink) throws IOException, PngValidationException {
        if (Files.size(path) > CapeAssetDecoder.MAX_ENCODED_BYTES) {
            throw CapeAssetDecoder.invalid(PngValidationException.Reason.OVERSIZED);
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 64 * 1024))) {
            new GifCapeReader(input, sink).decode();
        } catch (EOFException failure) {
            throw invalid();
        }
    }

    private GifCapeReader(DataInputStream input, CapeAssetDecoder.Sink sink) {
        this.input = input;
        this.sink = sink;
    }

    private void decode() throws IOException, PngValidationException {
        byte[] signature = input.readNBytes(6);
        if (!hasSignature(signature)) throw invalid();
        width = shortLE();
        height = shortLE();
        if (!PreparedCapeAsset.validFrameDimensions(width, height)) {
            throw CapeAssetDecoder.invalid(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS);
        }
        int packed = input.readUnsignedByte();
        backgroundIndex = input.readUnsignedByte();
        input.readUnsignedByte();
        if ((packed & 0x80) != 0) globalPalette = palette(1 << ((packed & 7) + 1));
        if (globalPalette != null) {
            if (backgroundIndex >= globalPalette.length / 3) throw invalid();
            int offset = backgroundIndex * 3;
            background = ((globalPalette[offset] & 255) << 24)
                    | ((globalPalette[offset + 1] & 255) << 16)
                    | ((globalPalette[offset + 2] & 255) << 8) | 255;
        }
        canvas = new byte[width * height * 4];
        sink.beginDynamic(width, height);
        int frames = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Cape preparation was interrupted");
            int marker = input.readUnsignedByte();
            if (marker == 0x3b) {
                if (input.read() != -1 || frames == 0 || controlPending) throw invalid();
                break;
            }
            if (marker == 0x21) {
                extension();
            } else if (marker == 0x2c) {
                if (++frames > 64) throw CapeAssetDecoder.invalid(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS);
                if (frames == 1 && background != 0 && control.transparentIndex < 0) {
                    fill(canvas, 0, 0, width, height, background);
                }
                applyDisposal();
                int x = shortLE(), y = shortLE(), rectWidth = shortLE(), rectHeight = shortLE();
                if (rectWidth <= 0 || rectHeight <= 0 || x >= width || y >= height
                        || (long) x + rectWidth > width || (long) y + rectHeight > height) throw invalid();
                int imagePacked = input.readUnsignedByte();
                if ((imagePacked & 0x18) != 0) throw invalid();
                byte[] palette = (imagePacked & 0x80) != 0 ? palette(1 << ((imagePacked & 7) + 1)) : globalPalette;
                if (palette == null) throw invalid();
                if (control.transparentIndex >= palette.length / 3) throw invalid();
                int minCodeSize = input.readUnsignedByte();
                if (minCodeSize < 2 || minCodeSize > 8) throw invalid();
                Control active = control;
                byte[] snapshot = active.disposal == 3 ? canvas.clone() : null;
                byte[] indices = decodeIndices(minCodeSize, rectWidth * rectHeight);
                int[] rows = rows(rectHeight, (imagePacked & 0x40) != 0);
                for (int sequence = 0; sequence < rectHeight; sequence++) {
                    int targetY = y + rows[sequence];
                    for (int column = 0; column < rectWidth; column++) {
                        int index = indices[sequence * rectWidth + column] & 255;
                        if (index >= palette.length / 3) throw invalid();
                        if (active.transparentIndex == index) continue;
                        int source = index * 3;
                        int target = (targetY * width + x + column) * 4;
                        canvas[target] = palette[source];
                        canvas[target + 1] = palette[source + 1];
                        canvas[target + 2] = palette[source + 2];
                        canvas[target + 3] = (byte) 255;
                    }
                }
                sink.writeFrame(frames - 1, canvas, active.delay == 0 ? 100 : active.delay * 10);
                previousDisposal = active.disposal;
                previousX = x;
                previousY = y;
                previousWidth = rectWidth;
                previousHeight = rectHeight;
                previousTransparentIndex = active.transparentIndex;
                previousSnapshot = snapshot;
                control = Control.DEFAULT;
                controlPending = false;
            } else {
                throw invalid();
            }
        }
        sink.completeDynamic(frames, repeatCount);
    }

    private void extension() throws IOException, PngValidationException {
        int label = input.readUnsignedByte();
        if (label == 0xf9) {
            if (controlPending || input.readUnsignedByte() != 4) throw invalid();
            int packed = input.readUnsignedByte();
            int disposal = (packed >>> 2) & 7;
            if (disposal > 3 || (packed & 0xe0) != 0) throw invalid();
            int delay = shortLE();
            int transparent = input.readUnsignedByte();
            if (input.readUnsignedByte() != 0) throw invalid();
            control = new Control(disposal, delay, (packed & 1) == 0 ? -1 : transparent);
            controlPending = true;
            metadata += 8;
        } else if (label == 0xff) {
            int length = input.readUnsignedByte();
            if (length != 11) throw invalid();
            byte[] name = input.readNBytes(11);
            if (name.length != 11) throw invalid();
            metadata += 14;
            String application = new String(name, java.nio.charset.StandardCharsets.US_ASCII);
            Subblocks blocks = new Subblocks(true);
            if (application.equals("NETSCAPE2.0") || application.equals("ANIMEXTS1.0")) {
                if (loopSeen || blocks.nextLength() != 3 || blocks.byteValue() != 1) throw invalid();
                repeatCount = blocks.byteValue() | blocks.byteValue() << 8;
                if (blocks.nextLength() != 0) throw invalid();
                loopSeen = true;
            } else blocks.drain();
        } else if (label == 0xfe || label == 0x01) {
            if (label == 0x01) {
                int length = input.readUnsignedByte();
                if (length != 12) throw invalid();
                byte[] header = input.readNBytes(length);
                if (header.length != length) throw invalid();
                metadata += length + 3;
            }
            new Subblocks(true).drain();
            if (label == 0x01) {
                control = Control.DEFAULT;
                controlPending = false;
            }
        } else {
            new Subblocks(true).drain();
        }
        if (metadata > 1024 * 1024) throw invalid();
    }

    private byte[] decodeIndices(int minimum, int pixelCount) throws IOException, PngValidationException {
        Subblocks blocks = new Subblocks(false);
        byte[] pixels = new byte[pixelCount];
        int clear = 1 << minimum;
        int end = clear + 1;
        int codeSize = minimum + 1;
        int next = end + 1;
        int previous = -1;
        int first = 0;
        short[] prefixes = new short[4096];
        byte[] suffixes = new byte[4096];
        byte[] stack = new byte[4096];
        int bitBuffer = 0, bitCount = 0, output = 0;
        boolean ended = false;
        boolean firstCode = true;
        while (true) {
            while (bitCount < codeSize) {
                int value = blocks.byteValue();
                if (value < 0) throw invalid();
                bitBuffer |= value << bitCount;
                bitCount += 8;
            }
            int code = bitBuffer & ((1 << codeSize) - 1);
            bitBuffer >>>= codeSize;
            bitCount -= codeSize;
            if (firstCode && code != clear) throw invalid();
            firstCode = false;
            if (code == clear) {
                codeSize = minimum + 1;
                next = end + 1;
                previous = -1;
                continue;
            }
            if (code == end) { ended = true; break; }
            int original = code;
            int depth = 0;
            if (code == next && previous >= 0) {
                stack[depth++] = (byte) first;
                code = previous;
            } else if (code >= next) throw invalid();
            while (code > end) {
                if (depth >= stack.length || code >= next) throw invalid();
                stack[depth++] = suffixes[code];
                code = Short.toUnsignedInt(prefixes[code]);
            }
            if (code >= clear || depth >= stack.length) throw invalid();
            first = code;
            stack[depth++] = (byte) first;
            if (output + depth > pixelCount) throw invalid();
            while (depth > 0) pixels[output++] = stack[--depth];
            if (previous >= 0 && next < 4096) {
                prefixes[next] = (short) previous;
                suffixes[next] = (byte) first;
                next++;
                if (next == (1 << codeSize) && codeSize < 12) codeSize++;
            }
            previous = original;
        }
        if (!ended || output != pixelCount) throw invalid();
        blocks.drain();
        return pixels;
    }

    private void applyDisposal() {
        if (previousDisposal == 2) fill(canvas, previousX, previousY, previousWidth, previousHeight,
                previousTransparentIndex >= 0 ? 0 : background);
        if (previousDisposal == 3 && previousSnapshot != null) {
            System.arraycopy(previousSnapshot, 0, canvas, 0, canvas.length);
        }
        previousSnapshot = null;
    }

    private void fill(byte[] pixels, int x, int y, int width, int height, int rgba) {
        int stride = this.width;
        for (int row = y; row < y + height; row++) for (int column = x; column < x + width; column++) {
            int offset = (row * stride + column) * 4;
            pixels[offset] = (byte) (rgba >>> 24);
            pixels[offset + 1] = (byte) (rgba >>> 16);
            pixels[offset + 2] = (byte) (rgba >>> 8);
            pixels[offset + 3] = (byte) rgba;
        }
    }

    private byte[] palette(int entries) throws IOException, PngValidationException {
        byte[] colors = input.readNBytes(entries * 3);
        if (colors.length != entries * 3) throw invalid();
        metadata += colors.length;
        if (metadata > 1024 * 1024) throw invalid();
        return colors;
    }

    private int shortLE() throws IOException { return input.readUnsignedByte() | input.readUnsignedByte() << 8; }

    private static int[] rows(int height, boolean interlaced) {
        int[] order = new int[height];
        if (!interlaced) {
            for (int i = 0; i < height; i++) order[i] = i;
        } else {
            int index = 0;
            int[] starts = {0, 4, 2, 1};
            int[] steps = {8, 8, 4, 2};
            for (int pass = 0; pass < 4; pass++) {
                for (int row = starts[pass]; row < height; row += steps[pass]) order[index++] = row;
            }
        }
        return order;
    }

    private static PngValidationException invalid() {
        return CapeAssetDecoder.invalid(PngValidationException.Reason.DECODE_FAILED);
    }

    private record Control(int disposal, int delay, int transparentIndex) {
        static final Control DEFAULT = new Control(0, 0, -1);
    }

    private final class Subblocks {
        private final boolean extension;
        private int left;
        private boolean ended;

        private Subblocks(boolean extension) { this.extension = extension; }

        private int nextLength() throws IOException, PngValidationException {
            if (ended || left != 0) throw invalid();
            if (++subblocks > 8_388_608) throw invalid();
            int length = input.readUnsignedByte();
            if (length == 0) ended = true;
            else if (extension) {
                metadata += length + 1;
                if (metadata > 1024 * 1024) throw invalid();
            }
            left = length;
            return length;
        }

        private int byteValue() throws IOException, PngValidationException {
            if (ended) return -1;
            if (left == 0 && nextLength() == 0) return -1;
            left--;
            return input.readUnsignedByte();
        }

        private void drain() throws IOException, PngValidationException {
            while (byteValue() >= 0) { }
        }
    }
}
