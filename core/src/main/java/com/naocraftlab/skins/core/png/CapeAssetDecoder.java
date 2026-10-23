package com.naocraftlab.skins.core.png;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.io.ByteArrayOutputStream;
import java.nio.channels.FileLock;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;

public final class CapeAssetDecoder {
    public static final long MAX_ENCODED_BYTES = 1_140_850_688L;
    public static final long MAX_BACKING_BYTES = 512L * 1024L * 1024L;
    public static final long MAX_WORKING_BYTES = 64L * 1024L * 1024L;
    private static final Semaphore ACTIVE_DECODER = new Semaphore(1, true);

    public static PreparedCapeAsset restore(byte[] descriptor, Path backing) throws IOException {
        PreparedCapeAsset asset = fileAsset(PreparedCapeAsset.Metadata.fromDescriptor(descriptor), backing);
        asset.verifyBacking();
        return asset;
    }

    @SuppressWarnings("try")
    public static PreparedCapeAsset restoreThumbnail(byte[] descriptor, Path backing) throws IOException {
        PreparedCapeAsset asset = fileAsset(PreparedCapeAsset.Metadata.fromDescriptor(descriptor), backing);
        try (PreparedCapeAsset.BackingLease ignored = asset.openThumbnailLease()) {
            return asset;
        }
    }

    private static PreparedCapeAsset fileAsset(PreparedCapeAsset.Metadata metadata, Path backing) {
        return new PreparedCapeAsset(metadata, new FileBacking(backing));
    }

    private record FileBacking(Path path) implements PreparedCapeAsset.Backing {
        private static final AtomicLong NEXT_LOCK_RANGE = new AtomicLong();
        @Override public PreparedCapeAsset.BackingLease open(PreparedCapeAsset.Metadata metadata) throws IOException {
            PreparedCapeAsset.BackingLease lease = openThumbnail(metadata);
            try {
                lease.verifyAll();
                return lease;
            } catch (IOException | RuntimeException | Error failure) {
                try { lease.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        @Override public PreparedCapeAsset.BackingLease openThumbnail(PreparedCapeAsset.Metadata metadata) throws IOException {
            FileChannel channel;
            try {
                channel = FileChannel.open(path, StandardOpenOption.READ);
            } catch (java.nio.file.NoSuchFileException missing) {
                throw new CapeIntegrityException("Cape backing is missing", missing);
            }
            FileLock lock = null;
            try {
                lock = channel.lock(NEXT_LOCK_RANGE.getAndIncrement(), 1, true);
                if (channel.size() != metadata.backingBytes()) throw new CapeIntegrityException("Cape backing size mismatch");
                return new FileBackingLease(metadata, channel, lock);
            } catch (IOException | RuntimeException | Error failure) {
                if (lock != null) {
                    try { lock.release(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
                try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    private static final class FileBackingLease implements PreparedCapeAsset.BackingLease {
        private final PreparedCapeAsset.Metadata metadata;
        private final int width;
        private final int height;
        private final FileChannel channel;
        private final FileLock lock;
        private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        private boolean verified;

        private FileBackingLease(PreparedCapeAsset.Metadata metadata, FileChannel channel, FileLock lock) {
            this.metadata = metadata;
            this.width = metadata.width();
            this.height = metadata.height();
            this.channel = channel;
            this.lock = lock;
        }

        public synchronized byte[] readFrame(int index) throws IOException {
            if (!channel.isOpen()) throw new java.nio.channels.ClosedChannelException();
            if (index < 0 || index >= metadata.frameCount()) throw new IndexOutOfBoundsException(index);
            verifyAll();
            byte[] pixels = new byte[Math.toIntExact(metadata.frameBytes())];
            readExact(channel, ByteBuffer.wrap(pixels), Math.multiplyExact((long) index, metadata.frameBytes()));
            if (!metadata.frameSha256().get(index).equals(HexFormat.of().formatHex(PreparedCapeAsset.sha256Digest().digest(pixels)))) {
                throw new CapeIntegrityException("Cape frame integrity failure");
            }
            return pixels;
        }

        @Override public synchronized PreparedCapeAsset.Thumbnail readThumbnail(int index) throws IOException {
            if (!channel.isOpen()) throw new java.nio.channels.ClosedChannelException();
            if (index < 0 || index >= metadata.frameCount()) throw new IndexOutOfBoundsException(index);
            if (channel.size() != metadata.backingBytes()) throw new CapeIntegrityException("Cape backing size mismatch");
            PreparedCapeAsset.Thumbnail thumbnail = thumbnail(metadata, index,
                    (position, row) -> readExact(channel, ByteBuffer.wrap(row), position));
            if (channel.size() != metadata.backingBytes()) throw new CapeIntegrityException("Cape backing size mismatch");
            return thumbnail;
        }

        @Override public synchronized void verifyAll() throws IOException {
            if (!verified) {
                if (!channel.isOpen()) throw new java.nio.channels.ClosedChannelException();
                verifyBacking(metadata, channel);
                verified = true;
            }
        }

        public synchronized byte[] framePng(int index) throws IOException {
            byte[] rgba = readFrame(index);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = (y * width + x) * 4;
                    row[x] = ((rgba[offset + 3] & 255) << 24) | ((rgba[offset] & 255) << 16)
                            | ((rgba[offset + 1] & 255) << 8) | (rgba[offset + 2] & 255);
                }
                image.setRGB(0, y, width, 1, row, 0, width);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder is unavailable");
            return output.toByteArray();
        }

        @Override public void close() throws IOException {
            if (!released.compareAndSet(false, true)) return;
            IOException failure = null;
            try { channel.close(); }
            catch (IOException error) { failure = error; }
            try { if (lock.isValid()) lock.release(); }
            catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }

    static PreparedCapeAsset.Thumbnail thumbnail(PreparedCapeAsset.Metadata metadata, int index,
            PreparedCapeAsset.RowReader source) throws IOException {
        int width = metadata.width(), height = metadata.height();
        int thumbWidth = Math.min(width, 512), thumbHeight = thumbWidth / 2;
        byte[] pixels = new byte[thumbWidth * thumbHeight * 4];
        byte[] row = new byte[width * 4];
        MessageDigest digest = PreparedCapeAsset.sha256Digest();
        long start = Math.multiplyExact((long) index, metadata.frameBytes());
        for (int y = 0; y < height; y++) {
            source.read(start + (long) y * row.length, row);
            digest.update(row);
            int outputY = y * thumbHeight / height;
            if (outputY * height / thumbHeight != y) continue;
            for (int x = 0; x < thumbWidth; x++) {
                int sourcePixel = (x * width / thumbWidth) * 4;
                int target = (outputY * thumbWidth + x) * 4;
                System.arraycopy(row, sourcePixel, pixels, target, 4);
            }
        }
        if (!metadata.frameSha256().get(index).equals(HexFormat.of().formatHex(digest.digest()))) {
            throw new CapeIntegrityException("Cape frame integrity failure");
        }
        return new PreparedCapeAsset.Thumbnail(thumbWidth, thumbHeight, pixels,
                HexFormat.of().formatHex(PreparedCapeAsset.sha256Digest().digest(pixels)));
    }


    private static void readExact(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, position);
            if (count < 0) throw new CapeIntegrityException("Cape frame is truncated");
            position += count;
        }
    }


    private static void verifyBacking(PreparedCapeAsset.Metadata metadata, FileChannel channel) throws IOException {
        int width = metadata.width(), height = metadata.height();
        List<String> frameSha256 = metadata.frameSha256();
        List<Integer> delaysMillis = metadata.delaysMillis();
        int repeatCount = metadata.repeatCount();
        boolean hasElytra = metadata.hasElytra();
        String backingSha256 = metadata.backingSha256(), visualSha256 = metadata.visualSha256();
        if (channel.size() != metadata.backingBytes()) throw new CapeIntegrityException("Cape backing size mismatch");
        MessageDigest digest = PreparedCapeAsset.sha256Digest();
        byte[] block = new byte[64 * 1024];
        boolean observedElytra = false;
        long position = 0;
        for (String expectedFrame : frameSha256) {
            MessageDigest frameDigest = PreparedCapeAsset.sha256Digest();
            long remaining = metadata.frameBytes();
            long pixelIndex = 0;
            while (remaining > 0) {
                int requested = (int) Math.min(block.length, remaining);
                readExact(channel, ByteBuffer.wrap(block, 0, requested), position);
                position += requested;
                frameDigest.update(block, 0, requested);
                digest.update(block, 0, requested);
                int scale = width / 64;
                for (int offset = 0; offset < requested; offset += 4, pixelIndex++) {
                    int x = (int) (pixelIndex % width);
                    int y = (int) (pixelIndex / width);
                    boolean wing = y < 2 * scale
                            ? x >= 24 * scale && x < 44 * scale
                            : y < 22 * scale && x >= 22 * scale && x < 46 * scale;
                    observedElytra |= wing && block[offset + 3] != 0;
                }
                remaining -= requested;
            }
            if (!expectedFrame.equals(HexFormat.of().formatHex(frameDigest.digest()))) {
                throw new CapeIntegrityException("Cape frame integrity failure");
            }
        }
        if (channel.size() != metadata.backingBytes() || !backingSha256.equals(HexFormat.of().formatHex(digest.digest()))) {
            throw new CapeIntegrityException("Cape backing integrity failure");
        }
        if (hasElytra != observedElytra) throw new CapeIntegrityException("Cape elytra metadata integrity failure");
        String computed = metadata.frameCount() == 1
                ? CapeAssetDecoder.Sink.staticIdentity(channel, width, height)
                : PreparedCapeAsset.animatedIdentity(width, height, frameSha256, delaysMillis, repeatCount);
        if (!visualSha256.equals(computed)) throw new CapeIntegrityException("Cape identity integrity failure");
    }


    public PreparedCapeAsset prepare(Path encoded, Path backing) throws IOException, PngValidationException {
        try {
            ACTIVE_DECODER.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Cape preparation was interrupted", interrupted);
        }
        try {
            return prepareLocked(encoded, backing);
        } finally {
            ACTIVE_DECODER.release();
        }
    }

    private PreparedCapeAsset prepareLocked(Path encoded, Path backing) throws IOException, PngValidationException {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(backing, "backing");
        if (Files.size(encoded) > MAX_ENCODED_BYTES) throw invalid(PngValidationException.Reason.OVERSIZED);
        if (Files.exists(backing)) throw new IOException("Cape backing already exists");
        Path parent = backing.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        boolean owned = false;
        try {
            Sink created = new Sink(backing);
            owned = true;
            try (Sink sink = created) {
                byte[] signature = new byte[8];
                try (var input = Files.newInputStream(encoded)) {
                    int length = input.readNBytes(signature, 0, signature.length);
                    if (length < 6) throw invalid(PngValidationException.Reason.BAD_SIGNATURE);
                }
                if (PngCapeReader.hasSignature(signature)) {
                    PngCapeReader.decode(encoded, sink);
                } else if (GifCapeReader.hasSignature(signature)) {
                    GifCapeReader.decode(encoded, sink);
                } else if ((signature[0] & 255) == 0xff && (signature[1] & 255) == 0xd8) {
                    decodeJpeg(encoded, sink);
                } else {
                    throw invalid(PngValidationException.Reason.BAD_SIGNATURE);
                }
                return sink.finish();
            }
        } catch (IOException | PngValidationException | RuntimeException failure) {
            if (owned) Files.deleteIfExists(backing);
            throw failure;
        }
    }

    private static void decodeJpeg(Path encoded, Sink sink) throws IOException, PngValidationException {
        PngFileReader.preflightJpeg(encoded, MAX_ENCODED_BYTES);
        try (ImageInputStream input = ImageIO.createImageInputStream(encoded.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid(PngValidationException.Reason.DECODE_FAILED);
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != 64 || reader.getHeight(0) != 32) {
                    throw invalid(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS);
                }
                BufferedImage image = reader.read(0);
                if (image == null) throw invalid(PngValidationException.Reason.DECODE_FAILED);
                sink.begin(64, 32, 1, 0);
                byte[] frame = new byte[64 * 32 * 4];
                for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) {
                    int argb = image.getRGB(x, y) | 0xff000000;
                    int offset = (y * 64 + x) * 4;
                    frame[offset] = (byte) (argb >>> 16);
                    frame[offset + 1] = (byte) (argb >>> 8);
                    frame[offset + 2] = (byte) argb;
                    frame[offset + 3] = (byte) (argb >>> 24);
                }
                sink.writeFrame(0, frame, 100);
                sink.forceElytra();
            } finally { reader.dispose(); }
        } catch (IllegalArgumentException failure) {
            throw invalid(PngValidationException.Reason.DECODE_FAILED);
        }
    }

    static PngValidationException invalid(PngValidationException.Reason reason) {
        return new PngValidationException(reason, "Invalid cape image");
    }

    static final class Sink implements AutoCloseable {
        private final Path backing;
        private final FileChannel channel;
        private int width;
        private int height;
        private int count;
        private int repeatCount;
        private int written;
        private boolean hasElytra;
        private final List<Integer> delays = new ArrayList<>();
        private boolean initialized;
        private byte[] scatterRow;

        Sink(Path backing) throws IOException {
            this.backing = backing;
            this.channel = FileChannel.open(backing, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
        }

        void begin(int width, int height, int count, int repeatCount) throws IOException, PngValidationException {
            if (initialized || !PreparedCapeAsset.validFrameDimensions(width, height) || count < 1 || count > 64
                    || repeatCount < 0 || repeatCount > 65_535) {
                throw invalid(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS);
            }
            long length = PreparedCapeAsset.backingBytes(width, height, count);
            if (length > MAX_BACKING_BYTES) throw invalid(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS);
            this.width = width;
            this.height = height;
            this.count = count;
            this.repeatCount = repeatCount;
            this.scatterRow = new byte[width * 4];
            initialized = true;
            channel.write(ByteBuffer.wrap(new byte[1]), length - 1);
        }

        void beginDynamic(int width, int height) throws PngValidationException {
            if (initialized || !PreparedCapeAsset.validFrameDimensions(width, height)) {
                throw invalid(PngValidationException.Reason.UNSUPPORTED_DIMENSIONS);
            }
            this.width = width;
            this.height = height;
            this.count = 64;
            this.scatterRow = new byte[width * 4];
            initialized = true;
        }

        void completeDynamic(int count, int repeatCount) throws PngValidationException {
            if (!initialized || count < 1 || count != written || count > 64
                    || repeatCount < 0 || repeatCount > 65_535) {
                throw invalid(PngValidationException.Reason.DECODE_FAILED);
            }
            this.count = count;
            this.repeatCount = count == 1 ? 0 : repeatCount;
        }

        void writeRow(int frame, int row, int xStart, int xStep, byte[] rgba, int pixels)
                throws IOException, PngValidationException {
            if (!initialized || frame < 0 || frame >= count || row < 0 || row >= height
                    || xStart < 0 || xStep < 1 || pixels < 0 || (long) xStart + (long) (pixels - 1) * xStep >= width
                    || rgba.length < pixels * 4) {
                throw invalid(PngValidationException.Reason.DECODE_FAILED);
            }
            long base = ((long) frame * width * height + (long) row * width) * 4;
            if (xStep == 1) {
                write(ByteBuffer.wrap(rgba, 0, pixels * 4), base + (long) xStart * 4);
            } else {
                ByteBuffer rowBuffer = ByteBuffer.wrap(scatterRow);
                long position = base;
                while (rowBuffer.hasRemaining()) {
                    int read = channel.read(rowBuffer, position);
                    if (read < 0) throw new CapeIntegrityException("Cape backing row is truncated");
                    position += read;
                }
                for (int x = 0; x < pixels; x++) {
                    System.arraycopy(rgba, x * 4, scatterRow, (xStart + x * xStep) * 4, 4);
                }
                write(ByteBuffer.wrap(scatterRow), base);
            }
        }

        void writeFrame(int frame, byte[] rgba, int delayMillis) throws IOException, PngValidationException {
            if (!initialized || frame != written || rgba.length != width * height * 4) {
                throw invalid(PngValidationException.Reason.DECODE_FAILED);
            }
            write(ByteBuffer.wrap(rgba), (long) frame * rgba.length);
            completeFrame(delayMillis);
        }

        void completeFrame(int delayMillis) throws PngValidationException {
            if (!initialized || written >= count || delayMillis <= 0 || delayMillis > 655_350) {
                throw invalid(PngValidationException.Reason.DECODE_FAILED);
            }
            delays.add(delayMillis);
            written++;
        }

        void forceElytra() { hasElytra = true; }

        private void write(ByteBuffer buffer, long position) throws IOException {
            while (buffer.hasRemaining()) {
                int count = channel.write(buffer, position);
                if (count <= 0) throw new IOException("Cape backing write made no progress");
                position += count;
            }
        }

        PreparedCapeAsset finish() throws IOException, PngValidationException {
            if (!initialized || written != count) throw invalid(PngValidationException.Reason.DECODE_FAILED);
            channel.force(true);
            if (channel.size() != PreparedCapeAsset.backingBytes(width, height, count)) throw new CapeIntegrityException("Cape backing size mismatch");
            MessageDigest backingDigest = PreparedCapeAsset.sha256Digest();
            List<String> frameHashes = new ArrayList<>(count);
            byte[] block = new byte[64 * 1024];
            for (int frame = 0; frame < count; frame++) {
                MessageDigest frameDigest = PreparedCapeAsset.sha256Digest();
                long remaining = (long) width * height * 4;
                long position = (long) frame * remaining;
                int pixelIndex = 0;
                while (remaining > 0) {
                    int length = (int) Math.min(block.length, remaining);
                    ByteBuffer buffer = ByteBuffer.wrap(block, 0, length);
                    while (buffer.hasRemaining()) {
                        int read = channel.read(buffer, position);
                        if (read < 0) throw new CapeIntegrityException("Cape backing is truncated");
                        position += read;
                    }
                    frameDigest.update(block, 0, length);
                    backingDigest.update(block, 0, length);
                    for (int offset = 0; offset < length; offset += 4, pixelIndex++) {
                        int x = pixelIndex % width;
                        int y = pixelIndex / width;
                        int scale = width / 64;
                        boolean wing = y < 2 * scale
                                ? x >= 24 * scale && x < 44 * scale
                                : y < 22 * scale && x >= 22 * scale && x < 46 * scale;
                        hasElytra |= wing && block[offset + 3] != 0;
                    }
                    remaining -= length;
                }
                frameHashes.add(HexFormat.of().formatHex(frameDigest.digest()));
            }
            String identity = count == 1 ? staticIdentity(channel, width, height) : PreparedCapeAsset.animatedIdentity(width, height,
                    frameHashes, delays, repeatCount);
            return fileAsset(new PreparedCapeAsset.Metadata(width, height, frameHashes, count == 1 ? List.of(100) : delays,
                    count == 1 ? 0 : repeatCount, identity, hasElytra,
                    HexFormat.of().formatHex(backingDigest.digest())), backing);
        }

        static String staticIdentity(FileChannel channel, int width, int height) throws IOException {
            return PreparedCapeAsset.staticIdentity(width, height,
                    (position, row) -> readExact(channel, ByteBuffer.wrap(row), position));
        }

        @Override public void close() throws IOException { channel.close(); }
    }
}
