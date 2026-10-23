package com.naocraftlab.skins.client;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageInputStreamImpl;


public abstract class AbstractTextureRegistry<R> implements TextureRegistry {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_DIRECT_UPLOAD_BYTES = 65L * 1024 * 1024;
    private static final int MAX_IMAGE_WIDTH = 2048;
    private static final int MAX_IMAGE_HEIGHT = 1024;
    private static final long MAX_RAW_GPU_BYTES = 256L * 1024 * 1024;
    private static long rawGpuBytes;

    private final Map<TextureKey, Entry<R>> entries = new HashMap<>();
    private final Map<TextureHandle, TextureKey> handles = new HashMap<>();
    private boolean closed;

    @Override
    public final TextureHandle register(TextureKind kind, String sha256, Path pngFile)
            throws IOException {
        Objects.requireNonNull(pngFile, "pngFile");
        return acquire(kind, sha256, () -> readBounded(pngFile, maximumBytes(kind)));
    }

    @Override
    public final TextureHandle register(TextureKind kind, String sha256, byte[] pngBytes)
            throws IOException {
        Objects.requireNonNull(pngBytes, "pngBytes");
        if (pngBytes.length > maximumBytes(kind)) {
            throw new IOException("Texture exceeds the in-memory size limit");
        }
        byte[] ownedBytes = pngBytes.clone();
        return acquire(kind, sha256, () -> ownedBytes);
    }

    @Override
    public final TextureHandle registerRgba(TextureKind kind, String sha256, int width,
            int height, byte[] rgba8, CpuLease cpuLease) throws IOException {
        Objects.requireNonNull(cpuLease, "cpuLease");
        try {
        Objects.requireNonNull(rgba8, "rgba8");
        if (kind != TextureKind.IMAGE || width <= 0 || height <= 0
                || width > MAX_IMAGE_WIDTH || height > MAX_IMAGE_HEIGHT
                || (long) width * height * 4 != rgba8.length) {
            throw new IOException("Invalid RGBA8 texture frame");
        }
        return acquireLoaded(kind, sha256, rgba8.length, cpuLease,
                () -> loadRgba(sha256, width, height, rgba8));
        } catch (IOException | RuntimeException | Error failure) {
            cpuLease.close();
            throw failure;
        }
    }

    @Override
    public final TextureUseValidity capture(TextureHandle handle) {
        Objects.requireNonNull(handle, "handle");
        checkClientThread();
        if (closed) return TextureUseValidity.INVALID;
        TextureKey key = handles.get(handle);
        Entry<R> entry = key == null ? null : entries.get(key);
        return entry != null && entry.handle() == handle
                ? entry.validity() : TextureUseValidity.INVALID;
    }

    @Override
    public final void release(TextureHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (closed) {
            return;
        }
        checkClientThread();
        TextureKey key = handles.get(handle);
        if (key == null) {
            return;
        }
        Entry<R> entry = entries.get(key);
        if (entry == null || !entry.handle().equals(handle)) {
            handles.remove(handle);
            return;
        }
        if (entry.references() > 1) {
            entries.put(key, entry.withReferences(entry.references() - 1));
            return;
        }
        entries.remove(key);
        handles.remove(handle);
        entry.validity().invalidate();
        try { unload(entry.resource()); }
        finally {
            releaseRawGpu(entry.gpuBytes());
            if (entry.cpuLease() != null) entry.cpuLease().close();
        }
    }

    @Override
    public final void close() {
        if (closed) {
            return;
        }
        checkClientThread();
        closed = true;
        entries.values().forEach(entry -> entry.validity().invalidate());
        RuntimeException aggregate = null;
        for (Entry<R> entry : entries.values()) {
            try {
                unload(entry.resource());
            } catch (RuntimeException failure) {
                if (aggregate == null) {
                    aggregate = failure;
                } else {
                    aggregate.addSuppressed(failure);
                }
            } finally {
                releaseRawGpu(entry.gpuBytes());
                if (entry.cpuLease() != null) entry.cpuLease().close();
            }
        }
        entries.clear();
        handles.clear();
        if (aggregate != null) {
            throw aggregate;
        }
    }


    protected abstract LoadedTexture<R> load(TextureKind kind, String sha256, byte[] pngBytes)
            throws IOException;

    protected LoadedTexture<R> loadRgba(String sha256, int width, int height, byte[] rgba8)
            throws IOException {
        throw new IOException("Raw frame upload is unavailable");
    }


    protected abstract void unload(R resource);


    protected abstract void checkClientThread();


    protected final boolean isClosed() {
        return closed;
    }


    protected final int liveTextureCount() {
        return entries.size();
    }

    private static int maximumBytes(TextureKind kind) {
        Objects.requireNonNull(kind, "kind");
        return EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES;
    }

    private static byte[] readBounded(Path path, int maximumBytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size > maximumBytes) throw new IOException("Texture exceeds the file size limit");
            if (size > MAX_DIRECT_UPLOAD_BYTES) return compactLargePng(channel, size);
            ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(size));
            long offset = 0;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, offset);
                if (read < 0) throw new IOException("Texture changed during upload read");
                if (read == 0) continue;
                offset += read;
            }
            if (channel.size() != size) throw new IOException("Texture changed during upload read");
            return buffer.array();
        }
    }

    private static byte[] compactLargePng(FileChannel channel, long length) throws IOException {
        try (ImageInputStream input = new BoundedChannelImageInputStream(channel, length)) {
            byte[] signature = new byte[8];
            input.readFully(signature);
            if (!java.util.Arrays.equals(signature,
                    new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})) {
                throw new IOException("Texture is not a PNG image");
            }
            input.seek(0);
            var readers = ImageIO.getImageReadersByFormatName("png");
            if (!readers.hasNext()) throw new IOException("PNG decoder is unavailable");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_IMAGE_WIDTH
                        || height > MAX_IMAGE_HEIGHT) {
                    throw new IOException("Texture dimensions exceed the upload limit");
                }
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("Texture is not a decodable PNG image");
                ByteArrayOutputStream compact = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "png", compact)) {
                    throw new IOException("PNG encoder is unavailable");
                }
                if (channel.size() != length) throw new IOException("Texture changed during upload read");
                if (compact.size() > MAX_DIRECT_UPLOAD_BYTES) {
                    throw new IOException("Decoded texture exceeds the upload limit");
                }
                return compact.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IndexOutOfBoundsException malformed) {
            throw new IOException("Texture is not a decodable PNG image", malformed);
        }
    }

    static final class BoundedChannelImageInputStream extends ImageInputStreamImpl {
        private final FileChannel channel;
        private final long bound;
        private final ByteBuffer single = ByteBuffer.allocate(1);

        BoundedChannelImageInputStream(FileChannel channel, long bound) {
            this.channel = channel;
            this.bound = bound;
        }

        @Override public int read() throws IOException {
            checkClosed();
            bitOffset = 0;
            if (streamPos >= bound) return -1;
            single.clear();
            int count = channel.read(single, streamPos);
            if (count <= 0) return -1;
            streamPos++;
            return single.get(0) & 255;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            checkClosed();
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            bitOffset = 0;
            if (length == 0) return 0;
            if (streamPos >= bound) return -1;
            int count = channel.read(ByteBuffer.wrap(bytes, offset,
                    (int) Math.min(length, bound - streamPos)), streamPos);
            if (count > 0) streamPos += count;
            return count;
        }

        @Override public long length() { return bound; }
    }


    protected record LoadedTexture<R>(TextureHandle handle, R resource) {
        public LoadedTexture {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(resource, "resource");
        }
    }

    private TextureHandle acquire(TextureKind kind, String sha256, ByteSource source)
            throws IOException {
        return acquireLoaded(kind, sha256, 0, null, () -> {
            byte[] sourceBytes = source.read();
            byte[] uploadBytes = switch (kind) {
                case PLAYER_SKIN -> PlayerSkinTextureNormalizer.normalizePng(sourceBytes);
                case IMAGE -> sourceBytes;
            };
            return load(kind, sha256, uploadBytes);
        });
    }

    private TextureHandle acquireLoaded(TextureKind kind, String sha256, long gpuBytes,
            CpuLease cpuLease, TextureLoader<R> source)
            throws IOException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sha256, "sha256");
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Expected a lowercase SHA-256 value");
        }
        if (closed) {
            throw new IllegalStateException("Texture registry is closed");
        }
        checkClientThread();

        TextureKey key = new TextureKey(kind, sha256);
        Entry<R> current = entries.get(key);
        if (current != null) {
            if (current.references() == Integer.MAX_VALUE) {
                throw new IllegalStateException("Texture reference count overflow");
            }
            if (gpuBytes > 0 && current.gpuBytes() == 0) {
                if ((long) current.handle().width() * current.handle().height() * 4 != gpuBytes) {
                    throw new IOException("Existing texture dimensions disagree with raw frame");
                }
                reserveRawGpu(gpuBytes);
                current = current.withBudget(gpuBytes, cpuLease);
            } else if (cpuLease != null) {
                cpuLease.close();
            }
            entries.put(key, current.withReferences(current.references() + 1));
            return current.handle();
        }

        reserveRawGpu(gpuBytes);
        LoadedTexture<R> loaded;
        try {
            loaded = Objects.requireNonNull(source.load(), "loadedTexture");
        } catch (IOException | RuntimeException | Error failure) {
            releaseRawGpu(gpuBytes);
            throw failure;
        }
        TextureKey collision = handles.get(loaded.handle());
        if (collision != null) {
            IllegalStateException failure = new IllegalStateException(
                    "Texture handle is already owned by a different content key");
            try {
                unload(loaded.resource());
            } catch (RuntimeException unloadFailure) {
                failure.addSuppressed(unloadFailure);
            } finally {
                releaseRawGpu(gpuBytes);
            }
            throw failure;
        }

        entries.put(key, new Entry<>(loaded.handle(), loaded.resource(), 1, gpuBytes, cpuLease,
                new UseValidity()));
        handles.put(loaded.handle(), key);
        return loaded.handle();
    }

    @FunctionalInterface
    private interface ByteSource {
        byte[] read() throws IOException;
    }

    @FunctionalInterface
    private interface TextureLoader<R> {
        LoadedTexture<R> load() throws IOException;
    }

    private static synchronized void reserveRawGpu(long bytes) throws IOException {
        if (bytes == 0) return;
        if (bytes < 0 || bytes > MAX_RAW_GPU_BYTES - rawGpuBytes) {
            throw new IOException("Prepared cape GPU budget is exhausted");
        }
        rawGpuBytes += bytes;
    }

    private static synchronized void releaseRawGpu(long bytes) {
        rawGpuBytes -= bytes;
    }

    private record TextureKey(TextureKind kind, String sha256) {
        private TextureKey {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    private static final class UseValidity implements TextureUseValidity {
        private boolean valid = true;

        @Override public boolean isValid() { return valid; }

        private void invalidate() { valid = false; }
    }

    private record Entry<R>(TextureHandle handle, R resource, int references, long gpuBytes,
            CpuLease cpuLease, UseValidity validity) {
        private Entry {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(resource, "resource");
            if (references <= 0 || gpuBytes < 0) {
                throw new IllegalArgumentException("Texture references must be positive");
            }
        }

        private Entry<R> withReferences(int value) {
            return new Entry<>(handle, resource, value, gpuBytes, cpuLease, validity);
        }

        private Entry<R> withBudget(long bytes, CpuLease lease) {
            return new Entry<>(handle, resource, references, bytes, lease, validity);
        }
    }
}
