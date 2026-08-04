package com.naocraftlab.skins.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;


public abstract class AbstractTextureRegistry<R> implements TextureRegistry {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_PLAYER_SKIN_BYTES = 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

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
        unload(entry.resource());
    }

    @Override
    public final void close() {
        if (closed) {
            return;
        }
        checkClientThread();
        closed = true;
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


    protected abstract void unload(R resource);


    protected abstract void checkClientThread();


    protected final boolean isClosed() {
        return closed;
    }


    protected final int liveTextureCount() {
        return entries.size();
    }

    private static int maximumBytes(TextureKind kind) {
        TextureKind requiredKind = Objects.requireNonNull(kind, "kind");
        return requiredKind == TextureKind.PLAYER_SKIN
                || requiredKind == TextureKind.PLAYER_SKIN_FEATURE_PRESERVING
                ? MAX_PLAYER_SKIN_BYTES
                : MAX_IMAGE_BYTES;
    }

    private static byte[] readBounded(Path path, int maximumBytes) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
                throw new IOException("Texture exceeds the file size limit");
            }
            return bytes;
        }
    }


    protected record LoadedTexture<R>(TextureHandle handle, R resource) {
        public LoadedTexture {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(resource, "resource");
        }
    }

    private TextureHandle acquire(TextureKind kind, String sha256, ByteSource source)
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
            entries.put(key, current.withReferences(current.references() + 1));
            return current.handle();
        }

        byte[] sourceBytes = source.read();
        byte[] uploadBytes = switch (kind) {
            case PLAYER_SKIN -> PlayerSkinTextureNormalizer.normalizePng(sourceBytes);
            case PLAYER_SKIN_FEATURE_PRESERVING -> PlayerSkinTextureNormalizer.normalizeFeaturePreservingPng(sourceBytes);
            case IMAGE -> sourceBytes;
        };
        LoadedTexture<R> loaded = Objects.requireNonNull(
                load(kind, sha256, uploadBytes), "loadedTexture");
        TextureKey collision = handles.get(loaded.handle());
        if (collision != null) {
            IllegalStateException failure = new IllegalStateException(
                    "Texture handle is already owned by a different content key");
            try {
                unload(loaded.resource());
            } catch (RuntimeException unloadFailure) {
                failure.addSuppressed(unloadFailure);
            }
            throw failure;
        }

        entries.put(key, new Entry<>(loaded.handle(), loaded.resource(), 1));
        handles.put(loaded.handle(), key);
        return loaded.handle();
    }

    @FunctionalInterface
    private interface ByteSource {
        byte[] read() throws IOException;
    }

    private record TextureKey(TextureKind kind, String sha256) {
        private TextureKey {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    private record Entry<R>(TextureHandle handle, R resource, int references) {
        private Entry {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(resource, "resource");
            if (references <= 0) {
                throw new IllegalArgumentException("Texture references must be positive");
            }
        }

        private Entry<R> withReferences(int value) {
            return new Entry<>(handle, resource, value);
        }
    }
}
