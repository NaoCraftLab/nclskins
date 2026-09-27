package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import java.io.IOException;
import java.nio.file.Path;

public final class LocalCapeImportAdapter implements LocalCapeImportSource {
    public Source read(Path path) throws IOException, PngValidationException {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        String lowerName = fileName.toLowerCase(java.util.Locale.ROOT);
        boolean jpegFile = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg");
        if (!jpegFile && !lowerName.endsWith(".png")) {
            throw new PngValidationException(PngValidationException.Reason.BAD_SIGNATURE,
                    "Unsupported cape file extension");
        }
        byte[] bytes;
        try (var input = java.nio.file.Files.newInputStream(path)) {
            bytes = input.readNBytes(com.naocraftlab.skins.core.png.PngValidator.DEFAULT_MAX_BYTES + 1);
        }
        if (bytes.length > PngValidator.DEFAULT_MAX_BYTES) {
            throw new PngValidationException(PngValidationException.Reason.OVERSIZED,
                    "Cape file exceeds the encoded texture limit");
        }
        boolean jpegBytes = bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8;
        if (jpegFile != jpegBytes) {
            throw new PngValidationException(PngValidationException.Reason.BAD_SIGNATURE,
                    "Cape file format does not match its extension");
        }
        return new Source(fileName, bytes);
    }
}
