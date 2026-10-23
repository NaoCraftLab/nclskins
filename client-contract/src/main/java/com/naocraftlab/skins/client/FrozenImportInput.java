package com.naocraftlab.skins.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

public final class FrozenImportInput implements ImportInput {
    public enum Rejection { INVALID, LIMIT }
    public static final class Rejected extends IOException {
        private static final long serialVersionUID = 1L;
        private final Rejection reason;
        public Rejected(Rejection reason) { super(reason.name()); this.reason = reason; }
        public Rejection reason() { return reason; }
    }
    private final Path path;
    private final FileChannel channel;
    private final Kind kind;
    private final long size;
    private int readers;
    private boolean closed;

    private FrozenImportInput(Path path, FileChannel channel, Kind kind, long size) {
        this.path = path; this.channel = channel; this.kind = kind; this.size = size;
    }

    public static FrozenImportInput capture(Path path) throws IOException {
        return capture(Files.newInputStream(Objects.requireNonNull(path)));
    }

    public static FrozenImportInput capture(InputStream source) throws IOException {
        Path staged = null;
        FileChannel output = null;
        try {
            Kind kind;
            long size = 0;
            try (source) {
                byte[] prefix = source.readNBytes(8);
                kind = sniff(prefix);
                long maximum = switch (kind) {
                    case PNG -> EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES;
                    case MODEL, PROJECT -> Long.MAX_VALUE;
                };
                staged = Files.createTempFile("nclskins-import-", ".input");
                output = FileChannel.open(staged, StandardOpenOption.READ, StandardOpenOption.WRITE);
                byte[] buffer = prefix;
                int count = prefix.length;
                while (count != -1) {
                    if (count > maximum - size) throw new Rejected(Rejection.LIMIT);
                    ByteBuffer block = ByteBuffer.wrap(buffer, 0, count);
                    while (block.hasRemaining()) output.write(block);
                    size += count;
                    if (buffer == prefix) buffer = new byte[8192];
                    count = source.read(buffer, 0, (int) Math.min(buffer.length, maximum - size + 1));
                }
            }
            FrozenImportInput result = new FrozenImportInput(staged, output, kind, size);
            output = null; staged = null;
            return result;
        } finally {
            if (output != null) output.close();
            if (staged != null) Files.deleteIfExists(staged);
        }
    }

    private static Kind sniff(byte[] prefix) throws Rejected {
        if (Arrays.equals(prefix, new byte[]{(byte)137,80,78,71,13,10,26,10})) return Kind.PNG;
        if (prefix.length > 0 && prefix[0] == 83) return Kind.MODEL;
        if (prefix.length >= 4 && prefix[0] == 80 && prefix[1] == 75
                && prefix[2] == 3 && prefix[3] == 4) return Kind.PROJECT;
        throw new Rejected(Rejection.INVALID);
    }

    public Kind kind() { return kind; }
    public long size() { return size; }

    public synchronized Reader open() throws IOException {
        if (closed) throw new IOException("Import input closed");
        readers++;
        return new Lease();
    }

    @Override public synchronized void close() throws IOException {
        closed = true;
        retire();
    }

    private void retire() throws IOException {
        if (closed && readers == 0) {
            try { channel.close(); } finally { Files.deleteIfExists(path); }
        }
    }

    private final class Lease extends ImportInput.Reader {
        private long position;
        private boolean released;
        private Lease() { }
        public long size() { return size; }
        public Kind kind() { return kind; }
        public Path file() throws IOException {
            if (released) throw new IOException("Import reader closed");
            return path;
        }
        public int readAt(long at, byte[] bytes, int offset, int count) throws IOException {
            Objects.checkFromIndexSize(offset, count, bytes.length);
            if (released) throw new IOException("Import reader closed");
            if (at < 0) throw new IllegalArgumentException("Negative position");
            if (count == 0) return 0;
            if (at >= size) return -1;
            return channel.read(ByteBuffer.wrap(bytes, offset, (int) Math.min(count, size - at)), at);
        }
        @Override public int read() throws IOException {
            byte[] value = new byte[1];
            return read(value, 0, 1) < 0 ? -1 : value[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            int n = readAt(position, bytes, offset, count);
            if (n > 0) position += n;
            return n;
        }
        @Override public void close() throws IOException {
            synchronized (FrozenImportInput.this) {
                if (!released) { released = true; readers--; retire(); }
            }
        }
    }
}
