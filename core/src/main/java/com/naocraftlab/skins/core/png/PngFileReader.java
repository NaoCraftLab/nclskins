package com.naocraftlab.skins.core.png;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class PngFileReader {
    private final PngValidator validator;

    public PngFileReader(PngValidator validator) { this.validator = Objects.requireNonNull(validator); }

    public PngInfo validate(Path path) throws IOException, PngValidationException {
        try (InputStream input = Files.newInputStream(path)) { return validate(input); }
    }
    public PngInfo validate(InputStream input) throws IOException, PngValidationException {
        return staged(input, source -> validator.inspect(source, false, false, null, false).info());
    }
    public PngInfo validateTexture(Path path) throws IOException, PngValidationException {
        try (InputStream input = Files.newInputStream(path)) {
            return staged(input, source -> validator.inspect(source, false, false, PngValidator.CapeAdmission.TEXTURE, false).info());
        }
    }
    public byte[] canonicalTexture(Path path) throws IOException, PngValidationException {
        try (InputStream input = Files.newInputStream(path)) { return staged(input, validator::canonicalTexture); }
    }
    public byte[] normalizeSkin(Path path) throws IOException, PngValidationException { return projectImport(path).pngBytes(); }
    public byte[] normalizeSkin(InputStream input) throws IOException, PngValidationException { return projectImport(input).pngBytes(); }
    public NormalizedSkin normalizeSkinWithVariant(Path path) throws IOException, PngValidationException { return projectImport(path); }
    public NormalizedSkin normalizeSkinWithVariant(InputStream input) throws IOException, PngValidationException { return projectImport(input); }
    public NormalizedSkin projectImport(Path path) throws IOException, PngValidationException {
        try (InputStream input = Files.newInputStream(path)) { return projectImport(input); }
    }
    public NormalizedSkin projectImport(InputStream input) throws IOException, PngValidationException {
        if (input instanceof com.naocraftlab.skins.client.FrozenImportInput.Reader frozen) {
            return validator.projectImport(new PngValidator.Source() {
                @Override public long size() { return frozen.size(); }
                @Override public int read(long position, byte[] bytes, int offset, int count) throws IOException {
                    return frozen.readAt(position, bytes, offset, count);
                }
            }, false);
        }
        return staged(input, source -> validator.projectImport(source, false));
    }
    public NormalizedSkin projectStandardImport(Path path) throws IOException, PngValidationException {
        try (InputStream input = Files.newInputStream(path)) { return projectStandardImport(input); }
    }
    public NormalizedSkin projectStandardImport(InputStream input) throws IOException, PngValidationException {
        return staged(input, source -> validator.projectImport(source, true));
    }
    public StoredSkinProjection projectStoredRender(Path path) throws IOException, PngValidationException {
        try (InputStream input = Files.newInputStream(path)) { return staged(input, validator::projectStoredRender); }
    }
    public String rawSkinPixelSha256(Path path) throws IOException, PngValidationException {
        try (InputStream input = Files.newInputStream(path)) { return staged(input, validator::rawSkinPixelSha256); }
    }
    public String renderSha256(Path path) throws IOException, PngValidationException {
        return validator.renderSha256(projectStoredRender(path).pngBytes());
    }
    public PngValidator.CapePng projectCanonicalCape(Path path) throws IOException, PngValidationException {
        try (InputStream input = Files.newInputStream(path)) { return staged(input, validator::projectCanonicalCape); }
    }

    private <T> T staged(InputStream input, Projection<T> projection) throws IOException, PngValidationException {
        Objects.requireNonNull(input, "input");
        Path staged = Files.createTempFile("nclskins-png-", ".png");
        try {
            long total = 0;
            byte[] block = new byte[8192];
            try (OutputStream output = Files.newOutputStream(staged)) {
                int count;
                while ((count = input.read(block)) != -1) {
                    if (count == 0) continue;
                    if (count > validator.maxBytes() - total) throw new PngValidationException(
                            PngValidationException.Reason.OVERSIZED, "PNG exceeds the configured size limit");
                    output.write(block, 0, count);
                    total += count;
                }
            }
            try (FileSource source = new FileSource(staged)) { return projection.apply(source); }
        } finally { Files.deleteIfExists(staged); }
    }

    @FunctionalInterface private interface Projection<T> {
        T apply(PngValidator.Source source) throws IOException, PngValidationException;
    }

    private static final class FileSource implements PngValidator.Source, AutoCloseable {
        private final FileChannel channel;
        private final long size;
        private FileSource(Path path) throws IOException {
            channel = FileChannel.open(path, StandardOpenOption.READ);
            try { size = channel.size(); }
            catch (IOException | RuntimeException | Error failure) {
                try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        @Override public long size() { return size; }
        @Override public int read(long position, byte[] bytes, int offset, int count) throws IOException {
            return channel.read(ByteBuffer.wrap(bytes, offset, count), position);
        }
        @Override public void close() throws IOException { channel.close(); }
    }

    static void preflightJpeg(Path path, long maxBytes) throws IOException, PngValidationException {
        try (FileSource source = new FileSource(path)) {
            JpegCapeDecoder.preflight(new JpegCapeDecoder.Bytes() {
                private final byte[] block = new byte[64 * 1024];
                private long start = -1;
                private int count;
                @Override public long size() { return source.size(); }
                @Override public int unsigned(long position) throws IOException {
                    if (position < start || position >= start + count) {
                        start = position;
                        int wanted = (int) Math.min(block.length, size() - position);
                        int total = 0;
                        while (total < wanted) {
                            int read = source.read(position + total, block, total, wanted - total);
                            if (read <= 0) throw new IOException("JPEG changed during read");
                            total += read;
                        }
                        count = wanted;
                    }
                    return Byte.toUnsignedInt(block[(int) (position - start)]);
                }
            }, maxBytes);
        }
    }
}
