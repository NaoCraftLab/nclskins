package com.naocraftlab.skins.core.png;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class JpegCapeDecoder {
    private static final int MAX_MARKERS = 4096;

    private JpegCapeDecoder() {
    }

    static boolean hasSignature(byte[] bytes) {
        return bytes != null && bytes.length >= 2
                && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8;
    }

    static byte[] toPng(byte[] bytes, int maxBytes) throws PngValidationException {
        preflight(bytes, maxBytes);
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
            if (source == null || source.getWidth() != 64 || source.getHeight() != 32) {
                throw failure(PngValidationException.Reason.DECODE_FAILED);
            }
            BufferedImage opaque = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
            opaque.setRGB(0, 0, 64, 32, source.getRGB(0, 0, 64, 32, null, 0, 64), 0, 64);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(opaque, "png", output)) {
                throw failure(PngValidationException.Reason.DECODE_FAILED);
            }
            return output.toByteArray();
        } catch (IOException | RuntimeException exception) {
            throw failure(PngValidationException.Reason.DECODE_FAILED);
        }
    }

    private static void preflight(byte[] bytes, int maxBytes) throws PngValidationException {
        if (bytes.length > maxBytes) {
            throw failure(PngValidationException.Reason.OVERSIZED);
        }
        if (!hasSignature(bytes)) {
            throw failure(PngValidationException.Reason.BAD_SIGNATURE);
        }
        int offset = 2;
        int markers = 0;
        boolean frame = false;
        boolean scan = false;
        boolean entropy = false;
        boolean scanData = false;
        while (offset < bytes.length && ++markers <= MAX_MARKERS) {
            if (entropy) {
                while (offset < bytes.length && unsigned(bytes[offset]) != 0xff) {
                    scanData = true;
                    offset++;
                }
                if (offset >= bytes.length) {
                    break;
                }
                offset++;
                while (offset < bytes.length && unsigned(bytes[offset]) == 0xff) {
                    offset++;
                }
                if (offset >= bytes.length) {
                    break;
                }
                int code = unsigned(bytes[offset]);
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
            if (unsigned(bytes[offset++]) != 0xff) {
                throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
            }
            while (offset < bytes.length && unsigned(bytes[offset]) == 0xff) {
                offset++;
            }
            if (offset >= bytes.length) {
                break;
            }
            int marker = unsigned(bytes[offset++]);
            if (marker == 0xd9) {
                if (!frame || !scan || offset != bytes.length) {
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
            if (offset + 2 > bytes.length) {
                break;
            }
            int length = unsigned(bytes[offset]) << 8 | unsigned(bytes[offset + 1]);
            if (length < 2 || length > bytes.length - offset) {
                throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
            }
            int data = offset + 2;
            if (marker == 0xc0 || marker == 0xc2) {
                if (frame || scan || length < 11) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
                }
                int components = unsigned(bytes[data + 5]);
                if (unsigned(bytes[data]) != 8 || components != 1 && components != 3 && components != 4
                        || length != 8 + 3 * components) {
                    throw failure(PngValidationException.Reason.MALFORMED_CHUNK);
                }
                int height = unsigned(bytes[data + 1]) << 8 | unsigned(bytes[data + 2]);
                int width = unsigned(bytes[data + 3]) << 8 | unsigned(bytes[data + 4]);
                if (width != 64 || height != 32) {
                    throw failure(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS);
                }
                frame = true;
            } else if (marker == 0xda) {
                if (!frame || length < 6 || length != 6 + 2 * unsigned(bytes[data])) {
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

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static PngValidationException failure(PngValidationException.Reason reason) {
        return new PngValidationException(reason, "JPEG cape is invalid");
    }
}
