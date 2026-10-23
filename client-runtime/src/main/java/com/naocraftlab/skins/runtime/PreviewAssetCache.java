package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.client.TextureRegistry.TextureKind;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;

import java.io.IOException;
import com.naocraftlab.skins.core.png.CapeIntegrityException;
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

    public synchronized void requestCape(K key,
            Supplier<CompletableFuture<Optional<CapePreviewSource>>> opener,
            Supplier<CompletableFuture<Optional<byte[]>>> staticLoader,
            Runnable registrationFailure) {
        requestCape(key, opener, staticLoader, true, registrationFailure);
    }

    public synchronized void requestCape(K key,
            Supplier<CompletableFuture<Optional<CapePreviewSource>>> opener,
            Supplier<CompletableFuture<Optional<byte[]>>> staticLoader,
            boolean playing, Runnable registrationFailure) {
        requestCape(key, key, opener, staticLoader, playing, registrationFailure);
    }

    public synchronized void requestCape(K key, Object assetIdentity,
            Supplier<CompletableFuture<Optional<CapePreviewSource>>> opener,
            Supplier<CompletableFuture<Optional<byte[]>>> staticLoader,
            boolean playing, Runnable registrationFailure) {
        requestCape(key, assetIdentity, opener, staticLoader, playing, false, registrationFailure);
    }

    public synchronized void requestCape(K key, Object assetIdentity,
            Supplier<CompletableFuture<Optional<CapePreviewSource>>> opener,
            Supplier<CompletableFuture<Optional<byte[]>>> staticLoader,
            boolean playing, boolean capeGlint, Runnable registrationFailure) {
        requestCape(key, assetIdentity, opener, staticLoader, playing, true, capeGlint,
                registrationFailure);
    }

    public synchronized void requestCape(K key, Object assetIdentity,
            Supplier<CompletableFuture<Optional<CapePreviewSource>>> opener,
            Supplier<CompletableFuture<Optional<byte[]>>> staticLoader,
            boolean playing, boolean hasElytra, boolean capeGlint,
            Runnable registrationFailure) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(assetIdentity, "assetIdentity");
        Objects.requireNonNull(opener, "opener");
        Objects.requireNonNull(staticLoader, "staticLoader");
        Objects.requireNonNull(registrationFailure, "registrationFailure");
        if (closed) return;
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        if (entry.assetIdentity != null && !entry.assetIdentity.equals(assetIdentity)) {
            replacePending(entry);
        }
        entry.assetIdentity = assetIdentity;
        entry.desiredGlint = capeGlint;
        entry.desiredElytra = hasElytra;
        if (entry.handle != null) {
            entry.installedGlint = capeGlint;
            entry.installedElytra = hasElytra;
        }
        if (entry.invalidPrepared) return;
        if (entry.playing != playing) {
            entry.playing = playing;
            if (entry.prepared != null) entry.prepared.playing(playing);
            if (!playing) restoreFirstFrame(entry);
        }
        if (!entry.preparedResolved) {
            if (entry.preparedOpening || entry.openFailures > 3) return;
            if (entry.openFailures > 0 && monotonicNanos.getAsLong() - entry.openRetryAtNanos < 0L) return;
            entry.preparedOpening = true;
            long attempt = ++entry.attempt;
            try {
                opener.get().whenComplete((source, failure) -> {
                    synchronized (PreviewAssetCache.this) {
                        if (closed || entry.invalidPrepared || entries.get(key) != entry || entry.attempt != attempt) {
                            if (source != null) source.ifPresent(this::closePrepared);
                            return;
                        }
                        entry.preparedOpening = false;
                        if (failure != null) {
                            diagnose(DiagnosticEvent.CLIENT_TEXTURE_LOAD_FAILED, failure);
                            if (CapeIntegrityException.isIntegrityFailure(failure)) invalidatePrepared(assetIdentity);
                            else scheduleOpenRetry(entry);
                            return;
                        }
                        entry.preparedResolved = true;
                        entry.openFailures = 0;
                        entry.prepared = source == null ? null : source.orElse(null);
                        if (entry.prepared == null) request(key, staticLoader, registrationFailure);
                        else {
                            entry.thumbnail = entry.prepared.thumbnail();
                            entry.prepared.playing(entry.playing);
                            requestPreparedFrame(entry, registrationFailure);
                        }
                    }
                });
            } catch (RuntimeException failure) {
                entry.preparedOpening = false;
                diagnose(DiagnosticEvent.CLIENT_TEXTURE_LOAD_FAILED, failure);
                if (CapeIntegrityException.isIntegrityFailure(failure)) invalidatePrepared(assetIdentity);
                else scheduleOpenRetry(entry);
            }
            return;
        }
        if (entry.prepared == null) request(key, staticLoader, registrationFailure);
        else requestPreparedFrame(entry, registrationFailure);
    }

    public synchronized void requestCapeWithPoster(K key, Object assetIdentity,
            Supplier<CompletableFuture<Optional<CapePreviewSource>>> opener,
            Supplier<CompletableFuture<Optional<ProviderCapePoster>>> posterLoader,
            boolean playing, boolean capeGlint, Runnable integrityFailure) {
        requestCape(key, assetIdentity, opener,
                () -> CompletableFuture.completedFuture(Optional.empty()), playing, true,
                capeGlint, () -> {});
        Entry entry = entries.get(key);
        if (closed || entry == null || entry.invalidPrepared || entry.handle != null
                || entry.posterInFlight
                || entry.posterFailures > 0 && monotonicNanos.getAsLong() - entry.posterRetryAtNanos < 0L) return;
        entry.posterInFlight = true;
        entry.posterIntegrityFailure = Objects.requireNonNull(integrityFailure);
        long attempt = ++entry.posterAttempt;
        try {
            posterLoader.get().whenComplete((poster, failure) -> {
                synchronized (PreviewAssetCache.this) {
                    if (closed || entries.get(key) != entry || entry.posterAttempt != attempt
                            || entry.invalidPrepared) return;
                    entry.posterInFlight = false;
                    if (failure != null) diagnose(DiagnosticEvent.CLIENT_TEXTURE_LOAD_FAILED, failure);
                    if (failure != null || poster == null || poster.isEmpty()) {
                        failPoster(entry);
                        return;
                    }
                    if (entry.handle != null) return;
                    ProviderCapePoster ready = poster.orElseThrow();
                    long bytes = ready.rgba8().length;
                    CapePreparationQueue.Reservation cpu = CapePreparationQueue.tryReserveCpu(2 * bytes);
                    if (cpu == null) return;
                    byte[] source = ready.copyRgba8();
                    try {
                        TextureHandle installed = textures.registerRgba(kind, ready.sha256(),
                                ready.width(), ready.height(), source, cpu);
                        source = null;
                        cpu.sourcePixelsReleased(bytes);
                        entry.handle = installed;
                        entry.frameHash = ready.sha256();
                        entry.firstFrameHandle = installed;
                        entry.firstFrameHash = ready.sha256();
                        entry.installedIndex = 0;
                        entry.posterFailures = 0;
                        entry.installedGlint = entry.desiredGlint;
                        entry.installedElytra = entry.desiredElytra;
                        releaseFallback(entry);
                    } catch (IOException | RuntimeException uploadFailure) {
                        cpu.close();
                        diagnose(DiagnosticEvent.CLIENT_TEXTURE_REGISTER_FAILED, uploadFailure);
                        failPoster(entry);
                    }
                }
            });
        } catch (RuntimeException failure) {
            entry.posterInFlight = false;
            diagnose(DiagnosticEvent.CLIENT_TEXTURE_LOAD_FAILED, failure);
            failPoster(entry);
        }
    }

    private void failPoster(Entry entry) {
        entry.posterFailures++;
        long delay = switch (entry.posterFailures) {
            case 1 -> FIRST_RETRY_NANOS;
            case 2 -> SECOND_RETRY_NANOS;
            default -> STEADY_RETRY_NANOS;
        };
        entry.posterRetryAtNanos = monotonicNanos.getAsLong() + delay;
    }

    private void requestPreparedFrame(Entry entry, Runnable registrationFailure) {
        if (entry.frameInFlight || entry.prepared == null || entry.frameFailures > 3) return;
        if (entry.frameFailures > 0 && monotonicNanos.getAsLong() - entry.frameRetryAtNanos < 0L) return;
        entry.frameInFlight = true;
        long attempt = ++entry.frameAttempt;
        boolean requestedPlaying = entry.playing;
        try {
            entry.prepared.nextFrame(entry.installedIndex).whenComplete((frame, failure) -> {
                synchronized (PreviewAssetCache.this) {
                    if (closed || entry.prepared == null || entry.frameAttempt != attempt) {
                        if (frame != null) frame.ifPresent(CapePreviewSource.Frame::close);
                        return;
                    }
                    entry.frameInFlight = false;
                    if (failure != null || frame == null) {
                        if (failure != null) diagnose(DiagnosticEvent.CLIENT_TEXTURE_LOAD_FAILED, failure);
                        if (CapeIntegrityException.isIntegrityFailure(failure)) {
                            invalidatePrepared(entry.assetIdentity);
                            return;
                        }
                        if (failure != null || frame == null) scheduleFrameRetry(entry);
                        return;
                    }
                    if (entry.playing != requestedPlaying) {
                        frame.ifPresent(CapePreviewSource.Frame::close);
                        return;
                    }
                    frame.ifPresent(next -> installPreparedFrame(entry, next, registrationFailure));
                }
            });
        } catch (RuntimeException failure) {
            entry.frameInFlight = false;
            diagnose(DiagnosticEvent.CLIENT_TEXTURE_LOAD_FAILED, failure);
            if (CapeIntegrityException.isIntegrityFailure(failure)) invalidatePrepared(entry.assetIdentity);
            else scheduleFrameRetry(entry);
        }
    }

    private void installPreparedFrame(Entry entry, CapePreviewSource.Frame frame,
            Runnable registrationFailure) {
        if (entry.thumbnail && entry.firstFrameHandle != null
                && frame.sha256().equals(entry.firstFrameHash)) {
            frame.close();
            TextureHandle previous = entry.handle;
            entry.handle = entry.firstFrameHandle;
            entry.frameHash = entry.firstFrameHash;
            entry.installedIndex = frame.index();
            entry.prepared.installed(frame.index());
            entry.frameFailures = 0;
            if (previous != null && !previous.equals(entry.firstFrameHandle)) releaseHandle(previous);
            return;
        }
        if (frame.sha256().equals(entry.frameHash) && entry.handle != null) {
            frame.close();
            entry.installedIndex = frame.index();
            entry.prepared.installed(frame.index());
            entry.frameFailures = 0;
            return;
        }
        TextureHandle successor;
        try {
            successor = textures.registerRgba(kind, frame.sha256(),
                    frame.width(), frame.height(), frame.rgba8(), frame.cpuLease());
            frame.sourcePixelsReleased();
        } catch (IOException | RuntimeException failure) {
            frame.close();
            diagnose(DiagnosticEvent.CLIENT_TEXTURE_REGISTER_FAILED, failure);
            scheduleFrameRetry(entry);
            notifyRegistrationFailure(registrationFailure);
            return;
        }
        TextureHandle previous = entry.handle;
        entry.handle = successor;
        entry.frameHash = frame.sha256();
        entry.installedGlint = entry.desiredGlint;
        entry.installedElytra = entry.desiredElytra;
        entry.installedIndex = frame.index();
        entry.prepared.installed(frame.index());
        entry.frameFailures = 0;
        if (entry.thumbnail && frame.index() == 0 && entry.firstFrameHandle == null) {
            entry.firstFrameHandle = successor;
            entry.firstFrameHash = frame.sha256();
        }
        if (previous != null && !previous.equals(entry.firstFrameHandle)) releaseHandle(previous);
        releaseFallback(entry);
    }

    private void replacePending(Entry entry) {
        entry.attempt++;
        entry.frameAttempt++;
        if (entry.prepared != null) closePrepared(entry.prepared);
        entry.prepared = null;
        entry.preparedOpening = false;
        entry.preparedResolved = false;
        entry.frameInFlight = false;
        entry.inFlight = false;
        entry.posterAttempt++;
        entry.posterInFlight = false;
        entry.posterIntegrityFailure = null;
        entry.posterFailures = 0;
        entry.invalidPrepared = false;
        entry.failures = 0;
        entry.openFailures = 0;
        entry.frameFailures = 0;
        TextureHandle current = entry.handle;
        TextureHandle first = entry.firstFrameHandle;
        if (first != null && !first.equals(current)) releaseHandle(first);
        if (current != null) {
            releaseFallback(entry);
            entry.fallbackHandle = current;
            entry.fallbackGlint = entry.installedGlint;
            entry.fallbackElytra = entry.installedElytra;
        }
        entry.handle = null;
        entry.firstFrameHandle = null;
        entry.firstFrameHash = null;
        entry.frameHash = null;
        entry.installedIndex = -1;
    }

    private void releaseFallback(Entry entry) {
        TextureHandle fallback = entry.fallbackHandle;
        entry.fallbackHandle = null;
        if (fallback != null) releaseHandle(fallback);
    }

    private void restoreFirstFrame(Entry entry) {
        if (!entry.thumbnail || entry.firstFrameHandle == null) return;
        TextureHandle previous = entry.handle;
        entry.handle = entry.firstFrameHandle;
        entry.frameHash = entry.firstFrameHash;
        entry.installedIndex = 0;
        entry.prepared.installed(0);
        if (previous != null && !previous.equals(entry.firstFrameHandle)) releaseHandle(previous);
    }

    public synchronized Optional<TextureHandle> handle(K key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key"));
        if (entry == null) {
            return Optional.empty();
        }
        TextureHandle visible = entry.handle != null ? entry.handle : entry.fallbackHandle;
        if (visible == null) return Optional.empty();
        Optional<NativePlayerSkinLifecycle.State> state = playerSkinState(visible);
        if (state.isPresent() && state.orElseThrow() != NativePlayerSkinLifecycle.State.READY) {
            return Optional.empty();
        }
        return Optional.of(visible);
    }

    public synchronized com.naocraftlab.skins.client.TextureUseValidity capture(K key) {
        return closed ? com.naocraftlab.skins.client.TextureUseValidity.INVALID
                : handle(key).map(textures::capture)
                        .orElse(com.naocraftlab.skins.client.TextureUseValidity.INVALID);
    }

    public synchronized boolean capeGlint(K key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key"));
        return entry != null && (entry.handle != null ? entry.installedGlint
                : entry.fallbackHandle != null && entry.fallbackGlint);
    }

    public synchronized boolean capeHasElytra(K key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key"));
        return entry != null && (entry.handle != null ? entry.installedElytra
                : entry.fallbackHandle != null && entry.fallbackElytra);
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
            entry.installedGlint = entry.desiredGlint;
            entry.installedElytra = entry.desiredElytra;
            releaseFallback(entry);
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

    private void scheduleOpenRetry(Entry entry) {
        entry.openFailures++;
        long delay = switch (entry.openFailures) {
            case 1 -> FIRST_RETRY_NANOS;
            case 2 -> SECOND_RETRY_NANOS;
            default -> STEADY_RETRY_NANOS;
        };
        entry.openRetryAtNanos = monotonicNanos.getAsLong() + delay;
    }

    private void scheduleFrameRetry(Entry entry) {
        entry.frameFailures++;
        long delay = switch (entry.frameFailures) {
            case 1 -> FIRST_RETRY_NANOS;
            case 2 -> SECOND_RETRY_NANOS;
            default -> STEADY_RETRY_NANOS;
        };
        entry.frameRetryAtNanos = monotonicNanos.getAsLong() + delay;
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
        if (entry == null) return;
        if (entry.prepared != null) {
            closePrepared(entry.prepared);
            entry.prepared = null;
        }
        TextureHandle current = entry.handle;
        TextureHandle first = entry.firstFrameHandle;
        TextureHandle fallback = entry.fallbackHandle;
        entry.handle = null;
        entry.firstFrameHandle = null;
        entry.fallbackHandle = null;
        if (current != null) releaseHandle(current);
        if (first != null && !first.equals(current)) releaseHandle(first);
        if (fallback != null && !fallback.equals(current) && !fallback.equals(first)) releaseHandle(fallback);
    }

    private void releaseHandle(TextureHandle handle) {
        try { textures.release(handle); }
        catch (RuntimeException failure) { diagnose(DiagnosticEvent.CLIENT_TEXTURE_RELEASE_FAILED, failure); }
    }

    private void invalidatePrepared(Object assetIdentity) {
        for (Entry entry : entries.values()) {
            if (assetIdentity.equals(entry.assetIdentity)) {
                if (entry.posterIntegrityFailure != null) {
                    try { entry.posterIntegrityFailure.run(); }
                    catch (RuntimeException failure) {
                        diagnose(DiagnosticEvent.UNEXPECTED_CLEANUP_FAILED, failure);
                    }
                }
                entry.frameAttempt++;
                release(entry);
                entry.invalidPrepared = true;
            }
        }
    }

    private void closePrepared(CapePreviewSource preview) {
        try { preview.close(); }
        catch (IOException failure) { diagnose(DiagnosticEvent.UNEXPECTED_CLEANUP_FAILED, failure); }
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
        private TextureHandle fallbackHandle;
        private boolean fallbackGlint;
        private boolean installedGlint;
        private boolean desiredGlint;
        private boolean fallbackElytra;
        private boolean installedElytra;
        private boolean desiredElytra;
        private TextureHandle firstFrameHandle;
        private String firstFrameHash;
        private boolean thumbnail;
        private CapePreviewSource prepared;
        private boolean preparedOpening;
        private boolean preparedResolved;
        private boolean invalidPrepared;
        private boolean playing = true;
        private Object assetIdentity;
        private boolean frameInFlight;
        private int installedIndex = -1;
        private String frameHash;
        private long frameAttempt;
        private boolean inFlight;
        private boolean posterInFlight;
        private long posterAttempt;
        private int posterFailures;
        private long posterRetryAtNanos;
        private Runnable posterIntegrityFailure;
        private int openFailures;
        private long openRetryAtNanos;
        private int frameFailures;
        private long frameRetryAtNanos;
        private int failures;
        private long retryAtNanos;
        private long attempt;
    }
}
