package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.client.TextureRegistry.TextureKind;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;


public final class PreviewAssetCache<K> implements AutoCloseable {
    private final TextureRegistry textures;
    private final TextureKind kind;
    private final Map<K, Entry> entries = new HashMap<>();
    private boolean closed;

    public PreviewAssetCache(TextureRegistry textures, TextureKind kind) {
        this.textures = Objects.requireNonNull(textures, "textures");
        this.kind = Objects.requireNonNull(kind, "kind");
    }


    public synchronized void request(
            K key,
            Supplier<CompletableFuture<Optional<byte[]>>> loader,
            Runnable registrationFailure) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(registrationFailure, "registrationFailure");
        if (closed || entries.containsKey(key)) {
            return;
        }
        Entry entry = new Entry();
        entries.put(key, entry);
        final CompletableFuture<Optional<byte[]>> load;
        try {
            load = Objects.requireNonNull(loader.get(), "preview load future");
        } catch (RuntimeException failure) {
            entries.remove(key, entry);
            throw failure;
        }
        load.whenComplete((bytes, failure) ->
                complete(key, entry, bytes, failure, registrationFailure));
    }

    public synchronized Optional<TextureHandle> handle(K key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key"));
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.handle);
    }


    public synchronized void retain(Set<K> desired) {
        Objects.requireNonNull(desired, "desired");
        if (closed) {
            return;
        }
        for (K key : new ArrayList<>(entries.keySet())) {
            if (!desired.contains(key)) {
                Entry removed = entries.remove(key);
                release(removed);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        entries.values().forEach(this::release);
        entries.clear();
    }

    private synchronized void complete(
            K key,
            Entry entry,
            Optional<byte[]> bytes,
            Throwable failure,
            Runnable registrationFailure) {
        if (closed || entries.get(key) != entry || failure != null || bytes == null) {
            return;
        }
        bytes.ifPresent(png -> register(entry, png, registrationFailure));
    }

    private void register(Entry entry, byte[] png, Runnable registrationFailure) {
        try {
            entry.handle = textures.register(kind, sha256(png), png);
        } catch (IOException | RuntimeException ignored) {
            registrationFailure.run();
        }
    }

    private void release(Entry entry) {
        if (entry != null && entry.handle != null) {
            TextureHandle handle = entry.handle;
            entry.handle = null;
            try {
                textures.release(handle);
            } catch (RuntimeException ignored) {


            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class Entry {
        private TextureHandle handle;
    }
}
