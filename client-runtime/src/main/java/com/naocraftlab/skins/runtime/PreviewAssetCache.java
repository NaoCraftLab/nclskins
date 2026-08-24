package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.client.TextureRegistry.TextureKind;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;

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
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;


public final class PreviewAssetCache<K> implements AutoCloseable {
    private static final long FIRST_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final long SECOND_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final long STEADY_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5L);

    private final TextureRegistry textures;
    private final TextureKind kind;
    private final DiagnosticSink diagnostics;
    private final LongSupplier monotonicNanos;
    private final Map<K, Entry> entries = new HashMap<>();
    private boolean closed;

    public PreviewAssetCache(
            TextureRegistry textures, TextureKind kind, DiagnosticSink diagnostics) {
        this(textures, kind, diagnostics, System::nanoTime);
    }

    PreviewAssetCache(
            TextureRegistry textures,
            TextureKind kind,
            DiagnosticSink diagnostics,
            LongSupplier monotonicNanos) {
        this.textures = Objects.requireNonNull(textures, "textures");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }


    public synchronized void request(
            K key,
            Supplier<CompletableFuture<Optional<byte[]>>> loader,
            Runnable registrationFailure) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(registrationFailure, "registrationFailure");
        if (closed) {
            return;
        }
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        long now = monotonicNanos.getAsLong();
        if (entry.handle != null) {
            Optional<NativePlayerSkinLifecycle.State> state = playerSkinState(entry.handle);
            if (state.filter(value -> value == NativePlayerSkinLifecycle.State.FAILED).isPresent()) {
                release(entry);
                fail(entry, now);
                notifyRegistrationFailure(registrationFailure);
            }
            return;
        }
        if (entry.inFlight
                || (entry.failures > 0 && now - entry.retryAtNanos < 0L)) {
            return;
        }
        entry.inFlight = true;
        long attempt = ++entry.attempt;
        final CompletableFuture<Optional<byte[]>> load;
        try {
            load = Objects.requireNonNull(loader.get(), "preview load future");
        } catch (RuntimeException failure) {
            diagnose(DiagnosticEvent.CLIENT_TEXTURE_LOAD_FAILED, failure);
            fail(entry, now);
            return;
        }
        load.whenComplete((bytes, failure) ->
                complete(key, entry, attempt, bytes, failure, registrationFailure));
    }

    public synchronized Optional<TextureHandle> handle(K key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key"));
        if (entry == null || entry.handle == null) {
            return Optional.empty();
        }
        Optional<NativePlayerSkinLifecycle.State> state = playerSkinState(entry.handle);
        if (state.isPresent() && state.orElseThrow() != NativePlayerSkinLifecycle.State.READY) {
            return Optional.empty();
        }
        return Optional.of(entry.handle);
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
            long attempt,
            Optional<byte[]> bytes,
            Throwable failure,
            Runnable registrationFailure) {
        if (closed || entries.get(key) != entry || entry.attempt != attempt) {
            return;
        }
        entry.inFlight = false;
        if (failure != null || bytes == null || bytes.isEmpty()) {
            if (failure != null) {
                diagnose(DiagnosticEvent.CLIENT_TEXTURE_LOAD_FAILED, failure);
            }
            scheduleRetry(entry);
            return;
        }
        if (!register(entry, bytes.orElseThrow())) {
            scheduleRetry(entry);
            notifyRegistrationFailure(registrationFailure);
        }
    }

    private boolean register(Entry entry, byte[] png) {
        try {
            entry.handle = textures.register(kind, sha256(png), png);
            return true;
        } catch (IOException | RuntimeException failure) {
            diagnose(DiagnosticEvent.CLIENT_TEXTURE_REGISTER_FAILED, failure);
            return false;
        }
    }

    private void notifyRegistrationFailure(Runnable registrationFailure) {
        try {
            registrationFailure.run();
        } catch (RuntimeException failure) {
            diagnose(DiagnosticEvent.UNEXPECTED_CLEANUP_FAILED, failure);
        }
    }

    private void scheduleRetry(Entry entry) {
        fail(entry, monotonicNanos.getAsLong());
    }

    private static void fail(Entry entry, long now) {
        entry.inFlight = false;
        entry.failures++;
        long delay = switch (entry.failures) {
            case 1 -> FIRST_RETRY_NANOS;
            case 2 -> SECOND_RETRY_NANOS;
            default -> STEADY_RETRY_NANOS;
        };
        entry.retryAtNanos = now + delay;
    }

    private void release(Entry entry) {
        if (entry != null && entry.handle != null) {
            TextureHandle handle = entry.handle;
            entry.handle = null;
            try {
                textures.release(handle);
            } catch (RuntimeException failure) {
                diagnose(DiagnosticEvent.CLIENT_TEXTURE_RELEASE_FAILED, failure);
            }
        }
    }

    private void diagnose(DiagnosticEvent event, Throwable failure) {
        diagnostics.report(event, () -> DiagnosticDetails.failure(failure));
    }

    private Optional<NativePlayerSkinLifecycle.State> playerSkinState(TextureHandle handle) {
        return kind == TextureKind.PLAYER_SKIN
                ? NativePlayerSkinLifecycle.managedState(handle.location())
                : Optional.empty();
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
        private boolean inFlight;
        private int failures;
        private long retryAtNanos;
        private long attempt;
    }
}
