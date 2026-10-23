package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class LocalCapeImportAdapter implements LocalCapeImportSource {
    @Override public PreparedCapeAsset prepare(Path encoded, Path backing) throws IOException, PngValidationException {
        return new CapeAssetDecoder().prepare(encoded, backing);
    }
    @Override public Path createBacking() throws IOException {
        Path backing = Files.createTempFile("nclskins-cape-", ".frames");
        Files.delete(backing);
        return backing;
    }

    @Override public void discardBacking(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    public Source read(Path path) throws IOException, PngValidationException {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        boolean jpeg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        boolean png = lower.endsWith(".png");
        boolean gif = lower.endsWith(".gif");
        if (!jpeg && !png && !gif) throw invalid(PngValidationException.Reason.BAD_SIGNATURE,
                "Unsupported cape file extension");
        if (Files.size(path) > CapeAssetDecoder.MAX_ENCODED_BYTES)
            throw invalid(PngValidationException.Reason.OVERSIZED,
                    "Cape file exceeds the encoded texture limit");
        CapePreparationQueue.Reservation scratch = CapePreparationQueue.tryReserveScratch(
                CapeAssetDecoder.MAX_ENCODED_BYTES + CapeAssetDecoder.MAX_BACKING_BYTES);
        if (scratch == null) throw new IOException("Cape preparation capacity unavailable");
        Path stage;
        try { stage = Files.createTempFile("nclskins-cape-import-", ".encoded"); }
        catch (IOException failure) { scratch.close(); throw failure; }
        boolean keep = false;
        try {
            long copied = 0;
            byte[] buffer = new byte[64 * 1024];
            try (var input = Files.newInputStream(path);
                    var output = Files.newOutputStream(stage, StandardOpenOption.TRUNCATE_EXISTING)) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    copied += count;
                    if (copied > CapeAssetDecoder.MAX_ENCODED_BYTES)
                        throw invalid(PngValidationException.Reason.OVERSIZED,
                                "Cape file exceeds the encoded texture limit");
                    output.write(buffer, 0, count);
                }
            }
            byte[] signature;
            try (var input = Files.newInputStream(stage)) { signature = input.readNBytes(8); }
            boolean matches = jpeg ? signature.length >= 3 && (signature[0] & 255) == 255
                    && (signature[1] & 255) == 216 && (signature[2] & 255) == 255
                    : gif ? signature.length >= 6 && signature[0] == 'G' && signature[1] == 'I'
                    && signature[2] == 'F' && signature[3] == '8'
                    && (signature[4] == '7' || signature[4] == '9') && signature[5] == 'a'
                    : signature.length == 8 && signature[0] == (byte) 0x89 && signature[1] == 'P'
                    && signature[2] == 'N' && signature[3] == 'G' && signature[4] == 13
                    && signature[5] == 10 && signature[6] == 26 && signature[7] == 10;
            if (!matches) throw invalid(PngValidationException.Reason.BAD_SIGNATURE,
                    "Cape file format does not match its extension");
            keep = true;
            return new Source(fileName, stage, scratch, Files::deleteIfExists);
        } finally {
            if (!keep) {
                try { Files.deleteIfExists(stage); }
                finally { scratch.close(); }
            }
        }
    }

    private static PngValidationException invalid(PngValidationException.Reason reason,
            String text) { return new PngValidationException(reason, text); }
}
