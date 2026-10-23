package com.naocraftlab.skins.core.png;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class PreparedCapeAsset {
    public static final long MAX_BACKING_BYTES = 512L * 1024L * 1024L;
    private static final byte[] DESCRIPTOR_MAGIC = {'N', 'C', 'L', 'C', 'A', 'P', 'E', '1'};
    private final Metadata metadata;
    private final Backing backing;

    PreparedCapeAsset(Metadata metadata, Backing backing) {
        this.metadata = Objects.requireNonNull(metadata);
        this.backing = Objects.requireNonNull(backing);
    }
    public int width() { return metadata.width(); }
    public int height() { return metadata.height(); }
    public int frameCount() { return metadata.frameCount(); }
    public List<String> frameSha256() { return metadata.frameSha256(); }
    public List<Integer> delaysMillis() { return metadata.delaysMillis(); }
    public int repeatCount() { return metadata.repeatCount(); }
    public String visualSha256() { return metadata.visualSha256(); }
    public boolean hasElytra() { return metadata.hasElytra(); }
    public Path backing() { return backing.path(); }
    public String backingSha256() { return metadata.backingSha256(); }
    public long frameBytes() { return metadata.frameBytes(); }
    public long backingBytes() { return metadata.backingBytes(); }
    public byte[] descriptorBytes() { return metadata.descriptorBytes(); }
    public BackingLease openBackingLease() throws IOException { return backing.open(metadata); }
    public BackingLease openThumbnailLease() throws IOException { return backing.openThumbnail(metadata); }
    public byte[] readFrame(int index) throws IOException {
        try (BackingLease lease = openBackingLease()) { return lease.readFrame(index); }
    }
    public byte[] framePng(int index) throws IOException {
        try (BackingLease lease = openBackingLease()) { return lease.framePng(index); }
    }
    @SuppressWarnings("try")
    public void verifyBacking() throws IOException {
        try (BackingLease ignored = openBackingLease()) { }
    }

    interface Backing {
        Path path();
        BackingLease open(Metadata metadata) throws IOException;
        BackingLease openThumbnail(Metadata metadata) throws IOException;
    }
    public interface BackingLease extends AutoCloseable {
        byte[] readFrame(int index) throws IOException;
        Thumbnail readThumbnail(int index) throws IOException;
        void verifyAll() throws IOException;
        byte[] framePng(int index) throws IOException;
        @Override void close() throws IOException;
    }
    public record Thumbnail(int width, int height, byte[] rgba8, String sha256) {}

    public record Metadata(int width, int height, List<String> frameSha256, List<Integer> delaysMillis,
            int repeatCount, String visualSha256, boolean hasElytra, String backingSha256) {
        public Metadata {
            frameSha256 = List.copyOf(frameSha256);
            delaysMillis = List.copyOf(delaysMillis);
            if (!validFrameDimensions(width, height) || frameSha256.isEmpty()
                    || frameSha256.size() > 64 || frameSha256.size() != delaysMillis.size()
                    || repeatCount < 0 || repeatCount > 65_535
                    || PreparedCapeAsset.backingBytes(width, height, frameSha256.size()) > MAX_BACKING_BYTES
                    || frameSha256.stream().anyMatch(hash -> !validHash(hash))
                    || delaysMillis.stream().anyMatch(delay -> delay == null || delay < 1 || delay > 655_350)
                    || !validHash(visualSha256) || !validHash(backingSha256)) {
                throw new IllegalArgumentException("Invalid prepared cape metadata");
            }
        }
        public int frameCount() { return frameSha256.size(); }
        public long frameBytes() { return Math.multiplyExact(Math.multiplyExact((long) width, height), 4L); }
        public long backingBytes() { return Math.multiplyExact(frameBytes(), frameCount()); }
        public byte[] descriptorBytes() {
            int size = DESCRIPTOR_MAGIC.length + 4 * 5 + 1 + 32 + 32 + frameCount() * (4 + 32);
            ByteBuffer buffer = ByteBuffer.allocate(size);
            buffer.put(DESCRIPTOR_MAGIC).putInt(1).putInt(width).putInt(height).putInt(frameCount()).putInt(repeatCount);
            buffer.put((byte) (hasElytra ? 1 : 0));
            buffer.put(HexFormat.of().parseHex(visualSha256)).put(HexFormat.of().parseHex(backingSha256));
            for (int index = 0; index < frameCount(); index++) {
                buffer.putInt(delaysMillis.get(index));
                buffer.put(HexFormat.of().parseHex(frameSha256.get(index)));
            }
            return buffer.array();
        }

        public static Metadata fromDescriptor(byte[] bytes) throws IOException {
            if (bytes.length > 64 * 1024 || bytes.length < 8 + 20 + 1 + 64 + 36) {
                throw new CapeIntegrityException("Invalid cape descriptor size");
            }
            ByteBuffer input = ByteBuffer.wrap(bytes);
            for (byte expected : DESCRIPTOR_MAGIC) if (input.get() != expected) throw new CapeIntegrityException("Invalid cape descriptor magic");
            if (input.getInt() != 1) throw new CapeIntegrityException("Unsupported cape descriptor version");
            int width = input.getInt(), height = input.getInt(), count = input.getInt(), repeats = input.getInt();
            byte elytra = input.get();
            if (!validFrameDimensions(width, height) || count < 1 || count > 64
                    || input.remaining() != 64 + count * 36 || repeats < 0 || repeats > 65_535
                    || (elytra != 0 && elytra != 1)) throw new CapeIntegrityException("Invalid cape descriptor metadata");
            byte[] hash = new byte[32];
            input.get(hash);
            String visual = HexFormat.of().formatHex(hash);
            input.get(hash);
            String backingHash = HexFormat.of().formatHex(hash);
            java.util.ArrayList<Integer> delays = new java.util.ArrayList<>(count);
            java.util.ArrayList<String> frameHashes = new java.util.ArrayList<>(count);
            for (int frame = 0; frame < count; frame++) {
                delays.add(input.getInt());
                input.get(hash);
                frameHashes.add(HexFormat.of().formatHex(hash));
            }
            Metadata metadata;
            try {
                metadata = new Metadata(width, height, frameHashes, delays, repeats, visual,
                        elytra == 1, backingHash);
            } catch (IllegalArgumentException failure) { throw new CapeIntegrityException("Invalid cape descriptor metadata", failure); }
            return metadata;
        }
    }

    public static boolean validFrameDimensions(int width, int height) {
        return (width == 64 || width == 128 || width == 256 || width == 512
                || width == 1024 || width == 2048) && height == width / 2;
    }

    public static long backingBytes(int width, int height, int count) {
        if (!validFrameDimensions(width, height) || count < 1 || count > 64) {
            throw new IllegalArgumentException("Unsupported cape geometry");
        }
        return Math.multiplyExact(Math.multiplyExact(Math.multiplyExact((long) width, height), 4L), count);
    }

    @FunctionalInterface interface RowReader {
        void read(long position, byte[] row) throws IOException;
    }

    static String staticIdentity(int width, int height, RowReader source) throws IOException {
        MessageDigest digest = sha256Digest();
        if (width > 64) digest.update(ByteBuffer.allocate(8).putInt(width).putInt(height).array());
        byte[] row = new byte[width * 4];
        byte[] pixel = new byte[4];
        for (int y = 0; y < height; y++) {
            source.read((long) y * row.length, row);
            for (int x = 0; x < width; x++) {
                int offset = x * 4;
                pixel[0] = row[offset + 3];
                pixel[1] = row[offset];
                pixel[2] = row[offset + 1];
                pixel[3] = row[offset + 2];
                digest.update(pixel);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String animatedIdentity(int width, int height, List<String> hashes,
            List<Integer> delays, int repeats) {
        MessageDigest digest = sha256Digest();
        digest.update("NCL-CAPE-ANIMATION-1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        digest.update(ByteBuffer.allocate(16).putInt(width).putInt(height).putInt(hashes.size()).putInt(repeats).array());
        for (int i = 0; i < hashes.size(); i++) {
            digest.update(HexFormat.of().parseHex(hashes.get(i)));
            digest.update(ByteBuffer.allocate(4).putInt(delays.get(i)).array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
