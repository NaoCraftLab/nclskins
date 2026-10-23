package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.core.png.CapeTimeline;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

public final class PreparedCapePreview implements CapePreviewSource {

    private final CatalogRead.PreparedCapeLease owner;
    private PreparedCapeAsset.BackingLease backing;
    private volatile boolean reopenBacking;
    private final PreparedCapeAsset asset;
    private final CapeTimeline timeline;
    private final Executor worker;
    private final ClientExecutor clientExecutor;
    private final LongSupplier monotonicNanos;
    private final boolean thumbnail;
    private volatile boolean closed;
    private boolean playing = true;
    private volatile boolean verified;
    private long startedNanos;
    private boolean started;

    static PreparedCapePreview open(CatalogRead.PreparedCapeLease owner,
            Executor worker, ClientExecutor clientExecutor) throws IOException {
        return open(owner, worker, clientExecutor, System::nanoTime, false);
    }

    static PreparedCapePreview open(CatalogRead.PreparedCapeLease owner,
            Executor worker, ClientExecutor clientExecutor, LongSupplier monotonicNanos) throws IOException {
        return open(owner, worker, clientExecutor, monotonicNanos, false);
    }

    static PreparedCapePreview open(CatalogRead.PreparedCapeLease owner,
            Executor worker, ClientExecutor clientExecutor, LongSupplier monotonicNanos,
            boolean thumbnail) throws IOException {
        Objects.requireNonNull(owner, "owner");
        try {
            return new PreparedCapePreview(owner, worker, clientExecutor, monotonicNanos, thumbnail);
        } catch (IOException | RuntimeException | Error failure) {
            try { owner.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private PreparedCapePreview(CatalogRead.PreparedCapeLease owner,
            Executor worker, ClientExecutor clientExecutor, LongSupplier monotonicNanos,
            boolean thumbnail) throws IOException {
        this.owner = owner;
        this.asset = owner.asset();
        this.worker = Objects.requireNonNull(worker, "worker");
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.thumbnail = thumbnail;
        this.playing = !thumbnail;
        this.verified = !thumbnail;
        this.backing = thumbnail ? asset.openThumbnailLease() : asset.openBackingLease();
        int[] delays = asset.delaysMillis().stream().mapToInt(Integer::intValue).toArray();
        this.timeline = new CapeTimeline(delays,
                asset.repeatCount() == 0 ? 0 : (long) asset.repeatCount() + 1);
    }

    public int frameCount() { return asset.frameCount(); }

    @Override public boolean thumbnail() { return thumbnail; }

    @Override public synchronized void playing(boolean requested) {
        if (closed || !thumbnail || playing == requested) return;
        playing = requested;
        started = false;
        if (requested) {
            startedNanos = monotonicNanos.getAsLong();
            started = true;
        }
    }

    public synchronized void installed(int index) {
        if (closed) return;
        if (index == 0 && !started) {
            startedNanos = monotonicNanos.getAsLong();
            started = true;
        }
    }

    public CompletableFuture<Optional<Frame>> nextFrame(int installedIndex) {
        CompletableFuture<Optional<Frame>> result = new CompletableFuture<>();
        if (closed) {
            result.complete(Optional.empty());
            return result;
        }
        long started;
        boolean animate;
        synchronized (this) { started = startedNanos; animate = playing; }
        int desired = installedIndex < 0 || !animate ? 0 : timeline.frameAt(
                Math.max(0, (monotonicNanos.getAsLong() - started) / 1_000_000L));
        if (desired == installedIndex && (!thumbnail || !animate || verified)) {
            result.complete(Optional.empty());
            return result;
        }
        try {
            worker.execute(() -> {
                Frame frame = null;
                try {
                    if (!closed) {
                        reopenBacking();
                        if (thumbnail && animate && !verified) {
                            backing.verifyAll();
                            verified = true;
                        }
                        if (desired == installedIndex) {
                            result.complete(Optional.empty());
                            return;
                        }
                        int frameWidth = thumbnail ? Math.min(asset.width(), 512) : asset.width();
                        int frameHeight = frameWidth / 2;
                        long retained = Math.multiplyExact(Math.multiplyExact((long) frameWidth, frameHeight), 8L);
                        long transientRow = thumbnail ? asset.width() * 4L : 0;
                        CapePreparationQueue.Reservation cpu = CapePreparationQueue.tryReserveCpu(
                                Math.addExact(retained, transientRow));
                        if (cpu != null) {
                            try {
                                PreparedCapeAsset.Thumbnail small = thumbnail ? backing.readThumbnail(desired) : null;
                                byte[] rgba = thumbnail ? small.rgba8() : backing.readFrame(desired);
                                if (!closed) {
                                    if (transientRow != 0) cpu.shrinkTo(retained);
                                    frame = new Frame(desired,
                                            thumbnail ? small.sha256() : asset.frameSha256().get(desired),
                                            frameWidth, frameHeight, rgba, cpu);
                                    cpu = null;
                                }
                            } finally {
                                if (cpu != null) cpu.close();
                            }
                        } else {
                            throw new IllegalStateException("Prepared cape frame memory is temporarily unavailable");
                        }
                    }
                    Frame ready = frame;
                    try {
                        clientExecutor.execute(() -> {
                            if (closed) {
                                if (ready != null) ready.close();
                                result.complete(Optional.empty());
                            } else {
                                result.complete(Optional.ofNullable(ready));
                            }
                        });
                    } catch (RuntimeException rejected) {
                        if (ready != null) ready.close();
                        result.completeExceptionally(rejected);
                    }
                } catch (IOException | RuntimeException failure) {
                    if (failure instanceof java.nio.channels.ClosedChannelException) reopenBacking = true;
                    if (frame != null) frame.close();
                    result.completeExceptionally(failure);
                } catch (Error failure) {
                    if (frame != null) frame.close();
                    throw failure;
                }
            });
        } catch (RuntimeException rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    private synchronized void reopenBacking() throws IOException {
        if (closed || !reopenBacking) return;
        backing.close();
        backing = asset.openBackingLease();
        verified = true;
        reopenBacking = false;
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try { backing.close(); } catch (IOException error) { failure = error; }
        try { owner.close(); } catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

}
