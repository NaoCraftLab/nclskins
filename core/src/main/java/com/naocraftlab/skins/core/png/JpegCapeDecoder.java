package com.naocraftlab.skins.core.png;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;

final class JpegCapeDecoder {
    private static final int MAX_MARKERS = 4096;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;

    private JpegCapeDecoder() {
    }

    static boolean hasSignature(byte[] bytes) {
        return bytes != null && bytes.length >= 2
                && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8;
    }

    static byte[] toPng(byte[] bytes, int maxBytes) throws PngValidationException {
        preflight(bytes, maxBytes);
        try (var input = PngValidator.memoryInput(bytes)) {
            BufferedImage source = ImageIO.read(input);
            if (source == null || source.getWidth() != 64 || source.getHeight() != 32) {
                throw failure(PngValidationException.Reason.DECODE_FAILED);
            }
            BufferedImage opaque = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
            opaque.setRGB(0, 0, 64, 32, source.getRGB(0, 0, 64, 32, null, 0, 64), 0, 64);
            return PngValidator.encode(opaque);
        } catch (IOException | RuntimeException exception) {
            throw failure(PngValidationException.Reason.DECODE_FAILED);
        }
    }

    private static void preflight(byte[] bytes, int maxBytes) throws PngValidationException {
        try {
            preflight(new ByteArrayBytes(bytes), maxBytes);
        } catch (IOException impossible) {
            throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
        }
    }

    static void preflight(Bytes bytes, long maxBytes) throws IOException, PngValidationException {
        long size = bytes.size();
        if (size > maxBytes) {
            throw failure(PngValidationException.Reason.OVERSIZED);
        }
        if (size < 2 || bytes.unsigned(0) != 0xff || bytes.unsigned(1) != 0xd8) {
            throw failure(PngValidationException.Reason.BAD_SIGNATURE);
        }
        long offset = 2;
        int markers = 0;
        int metadata = 0;
        boolean frame = false;
        boolean scan = false;
        boolean entropy = false;
        boolean scanData = false;
        while (offset < size && ++markers <= MAX_MARKERS) {
            if (entropy) {
                while (offset < size && bytes.unsigned(offset) != 0xff) {
                    scanData = true;
                    offset++;
                }
                if (offset >= size) {
                    break;
                }
                offset++;
                while (offset < size && bytes.unsigned(offset) == 0xff) {
                    offset++;
                }
                if (offset >= size) {
                    break;
                }
                int code = bytes.unsigned(offset);
                if (code == 0 || code >= 0xd0 && code <= 0xd7) {
                    scanData |= code == 0;
                    offset++;
                    continue;
                }
                if (!scanData) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
                }
                offset--;
                entropy = false;
            }
            if (bytes.unsigned(offset++) != 0xff) {
                throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
            }
            while (offset < size && bytes.unsigned(offset) == 0xff) {
                offset++;
            }
            if (offset >= size) {
                break;
            }
            int marker = bytes.unsigned(offset++);
            if (marker == 0xd9) {
                if (!frame || !scan || offset != size) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
                }
                return;
            }
            if (marker == 0x00 || marker == 0xd8 || marker >= 0xd0 && marker <= 0xd7
                    || marker == 0x01 || marker == 0xdc || marker == 0xde || marker == 0xdf
                    || marker >= 0xc0 && marker <= 0xcf && marker != 0xc0 && marker != 0xc2 && marker != 0xc4 && marker != 0xcc
                    || marker == 0xcc) {
                throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
            }
            if (offset + 2 > size) {
                break;
            }
            int length = bytes.unsigned(offset) << 8 | bytes.unsigned(offset + 1);
            if (length < 2 || length > size - offset) {
                throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
            }
            if (marker == 0xfe || marker >= 0xe0 && marker <= 0xef) {
                int payloadLength = length - 2;
                if (payloadLength > MAX_METADATA_BYTES - metadata) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
                }
                metadata += payloadLength;
            }
            long data = offset + 2;
            if (marker == 0xc0 || marker == 0xc2) {
                if (frame || scan || length < 11) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
                }
                int components = bytes.unsigned(data + 5);
                if (bytes.unsigned(data) != 8 || components != 1 && components != 3 && components != 4
                        || length != 8 + 3 * components) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
                }
                int height = bytes.unsigned(data + 1) << 8 | bytes.unsigned(data + 2);
                int width = bytes.unsigned(data + 3) << 8 | bytes.unsigned(data + 4);
                if (width != 64 || height != 32) {
                    throw failure(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS);
                }
                frame = true;
            } else if (marker == 0xda) {
                if (!frame || length < 6 || length != 6 + 2 * bytes.unsigned(data)) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
                }
                scan = true;
                entropy = true;
                scanData = false;
            } else if (!(marker == 0xc4 || marker == 0xdb || marker == 0xdd || marker == 0xfe
                    || marker >= 0xe0 && marker <= 0xef)) {
                throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
            }
            offset += length;
        }
        throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
    }

    interface Bytes {
        long size() throws IOException;
        int unsigned(long position) throws IOException;
    }

    private record ByteArrayBytes(byte[] data) implements Bytes {
        @Override public long size() { return data.length; }
        @Override public int unsigned(long position) { return JpegCapeDecoder.unsigned(data[(int) position]); }
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static PngValidationException failure(PngValidationException.Reason reason) {
        return new PngValidationException(reason, "JPEG cape is invalid");
    }
}
