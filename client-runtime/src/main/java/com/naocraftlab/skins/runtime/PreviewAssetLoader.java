package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

final class PreviewAssetLoader implements PreviewAssets {
    private final ClientExecutor clientExecutor;
    private final Executor worker;
    private final DiagnosticSink diagnostics;
    private final Map<String, byte[]> previewBytes = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Optional<byte[]>>> previewInFlight = new ConcurrentHashMap<>();
    private final Map<String, ProviderCapePoster> providerPosters = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, CompletableFuture<Optional<ProviderCapePoster>>> providerInFlight = new ConcurrentHashMap<>();
    private static final long MAX_POSTER_BYTES = 16L * 1024 * 1024;
    private long posterBytes;
    private long providerEpoch;
    private long catalogPreviewEpoch;
    private volatile boolean disposed;
    private final Map<String, SneakyDemand> sneakyDemand = new LinkedHashMap<>();
    private int activeSneaky;
    private final Map<String, SneakyResult> sneakyCache = new LinkedHashMap<>(32, .75f, true);
    private long sneakyCacheBytes;

    PreviewAssetLoader(ClientExecutor clientExecutor, Executor worker, DiagnosticSink diagnostics) {
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public long catalogEpoch() { return catalogPreviewEpoch; }

    @Override
    public synchronized SneakyLease prepareSneaky(byte[] bytes) {
        if (disposed) throw new IllegalStateException("Preview loader is closed");
        byte[] input = Objects.requireNonNull(bytes, "bytes").clone();
        String key;
        try {
            key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        SneakyDemand demand = sneakyDemand.get(key);
        SneakyResult cached = sneakyCache.get(key);
        if (cached != null) {
            SneakyRequest request = new SneakyRequest(key, new SneakyDemand(null));
            request.publication.complete(cached);
            return request;
        }
        if (demand == null) {
            while (sneakyDemand.size() >= 32) {
                var oldest = sneakyDemand.entrySet().stream().filter(entry -> !entry.getValue().started)
                        .findFirst().orElseThrow();
                sneakyDemand.remove(oldest.getKey());
                var retired = oldest.getValue();
                retired.input = null;
                java.util.List.copyOf(retired.leases).forEach(lease -> lease.publication.complete(SneakyResult.failure()));
                retired.leases.clear();
            }
            demand = new SneakyDemand(input);
            sneakyDemand.put(key, demand);
        }
        SneakyRequest request = new SneakyRequest(key, demand);
        demand.leases.add(request);
        pumpSneaky();
        return request;
    }

    private void pumpSneaky() {
        while (!disposed && activeSneaky < 4) {
            var next = sneakyDemand.entrySet().stream().filter(entry -> !entry.getValue().started).findFirst();
            if (next.isEmpty()) return;
            String key = next.orElseThrow().getKey();
            SneakyDemand demand = next.orElseThrow().getValue();
            demand.started = true;
            activeSneaky++;
            try {
                worker.execute(() -> {
                    SneakyResult ready;
                    try {
                        new com.naocraftlab.skins.core.png.PngValidator().projectStoredRender(demand.input);
                        ready = new com.naocraftlab.skins.core.png.SneakyCapeDecoder().decode(demand.input)
                                .map(cape -> new SneakyResult(SneakyStatus.VALUE, cape.bytes(),
                                        cape.renderSha256(), cape.hasElytra())).orElseGet(SneakyResult::absence);
                    } catch (Exception failure) {
                        diagnose(failure);
                        ready = SneakyResult.failure();
                    }
                    SneakyResult result = ready;
                    onClient(() -> finishSneaky(key, demand, result));
                });
            } catch (RuntimeException rejected) {
                finishSneaky(key, demand, SneakyResult.failure());
            }
        }
    }

    private synchronized void finishSneaky(String key, SneakyDemand demand, SneakyResult result) {
        activeSneaky--;
        boolean current = sneakyDemand.remove(key, demand);
        demand.input = null;
        if (!disposed && current && !demand.leases.isEmpty() && result.status() != SneakyStatus.FAILURE) {
            sneakyCache.put(key, result);
            sneakyCacheBytes += result.atlas() == null ? 0 : result.atlas().length;
            var iterator = sneakyCache.entrySet().iterator();
            while ((sneakyCache.size() > 32 || sneakyCacheBytes > 2 * 1024 * 1024) && iterator.hasNext()) {
                SneakyResult oldest = iterator.next().getValue();
                sneakyCacheBytes -= oldest.atlas() == null ? 0 : oldest.atlas().length;
                iterator.remove();
            }
        }
        for (SneakyRequest lease : java.util.List.copyOf(demand.leases)) {
            lease.publication.complete(disposed || !current ? SneakyResult.failure() : result);
        }
        demand.leases.clear();
        pumpSneaky();
    }

    private static final class SneakyDemand {
        byte[] input;
        final java.util.List<SneakyRequest> leases = new java.util.ArrayList<>();
        boolean started;
        SneakyDemand(byte[] input) { this.input = input; }
    }

    private final class SneakyRequest implements SneakyLease {
        final String key;
        final SneakyDemand demand;
        final CompletableFuture<SneakyResult> publication = new CompletableFuture<>();
        SneakyRequest(String key, SneakyDemand demand) { this.key = key; this.demand = demand; }
        public CompletableFuture<SneakyResult> result() { return publication; }
        public void close() {
            synchronized (PreviewAssetLoader.this) {
                demand.leases.remove(this);
                if (demand.leases.isEmpty() && !demand.started) sneakyDemand.remove(key, demand);
                publication.complete(SneakyResult.failure());
            }
        }
    }

    @Override
    public synchronized void invalidateSneaky() {
        var retired = java.util.List.copyOf(sneakyDemand.values());
        sneakyDemand.clear();
        sneakyCache.clear();
        sneakyCacheBytes = 0;
        for (SneakyDemand demand : retired) {
            if (!demand.started) demand.input = null;
            java.util.List.copyOf(demand.leases).forEach(lease -> lease.publication.complete(SneakyResult.failure()));
            demand.leases.clear();
        }
    }

    public void installWarmed(Map<String, byte[]> warmed, boolean replaceResources) {
        if (disposed) return;
        if (replaceResources) previewBytes.keySet().removeIf(key -> key.startsWith("resource:cape:"));
        warmed.forEach((key, bytes) -> previewBytes.put(key, bytes.clone()));
    }

    @Override
    public synchronized void installWarmedProviderPosters(Map<String, ProviderCapePoster> posters) {
        Objects.requireNonNull(posters, "posters");
        if (disposed) {
            posters.values().forEach(ProviderCapePoster::close);
            return;
        }
        posters.forEach(this::cachePoster);
    }

    @Override
    public synchronized void invalidateProviderPosters() {
        providerEpoch++;
        providerPosters.values().forEach(ProviderCapePoster::close);
        providerPosters.clear();
        posterBytes = 0;
        providerInFlight.values().forEach(future -> future.complete(Optional.empty()));
        providerInFlight.clear();
    }

    @Override
    public synchronized void invalidateProviderPoster(String key) {
        ProviderCapePoster removed = providerPosters.remove(key);
        if (removed != null) {
            posterBytes -= removed.rgba8().length;
            removed.close();
        }
        CompletableFuture<Optional<ProviderCapePoster>> pending = providerInFlight.remove(key);
        if (pending != null) pending.complete(Optional.empty());
    }

    @Override
    public synchronized CompletableFuture<Optional<ProviderCapePoster>> requestProviderPoster(
            String key, PosterSource source) {
        if (disposed) throw new IllegalStateException("Preview loader is closed");
        ProviderCapePoster cached = providerPosters.get(key);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));
        CompletableFuture<Optional<ProviderCapePoster>> existing = providerInFlight.get(key);
        if (existing != null) return existing;
        long epoch = providerEpoch;
        CompletableFuture<Optional<ProviderCapePoster>> result = new CompletableFuture<>();
        providerInFlight.put(key, result);
        try {
            worker.execute(() -> {
                Optional<ProviderCapePoster> loaded;
                try { loaded = Objects.requireNonNull(source.get()); }
                catch (Exception failure) {
                    diagnose(failure);
                    loaded = Optional.empty();
                }
                Optional<ProviderCapePoster> ready = loaded;
                onClient(() -> {
                    synchronized (PreviewAssetLoader.this) {
                        boolean current = providerInFlight.remove(key, result);
                        if (disposed || epoch != providerEpoch || !current) {
                            ready.ifPresent(ProviderCapePoster::close);
                            result.complete(Optional.empty());
                        } else {
                            ready.ifPresent(poster -> cachePoster(key, poster));
                            result.complete(ready.filter(poster -> providerPosters.get(key) == poster));
                        }
                    }
                });
            });
        } catch (RuntimeException failure) {
            providerInFlight.remove(key, result);
            diagnose(failure);
            result.complete(Optional.empty());
        }
        return result;
    }

    private void cachePoster(String key, ProviderCapePoster poster) {
        ProviderCapePoster previous = providerPosters.put(key, poster);
        if (previous != null && previous != poster) {
            posterBytes -= previous.rgba8().length;
            previous.close();
        }
        if (previous != poster) posterBytes += poster.rgba8().length;
        var iterator = providerPosters.entrySet().iterator();
        while (posterBytes > MAX_POSTER_BYTES && iterator.hasNext()) {
            ProviderCapePoster oldest = iterator.next().getValue();
            posterBytes -= oldest.rgba8().length;
            oldest.close();
            iterator.remove();
        }
    }

    @Override
    public synchronized void close() {
        disposed = true;
        invalidateSneaky();
        previewInFlight.values().forEach(future -> future.complete(Optional.empty()));
        previewInFlight.clear();
        previewBytes.clear();
        invalidateProviderPosters();
        providerInFlight.values().forEach(future -> future.complete(Optional.empty()));
        providerInFlight.clear();
    }

    private void onClient(Runnable action) {
        if (clientExecutor.isClientThread()) action.run();
        else clientExecutor.execute(action);
    }

    private void diagnose(Throwable failure) {
        diagnostics.report(DiagnosticEvent.CLIENT_PREVIEW_SOURCE_FAILED,
                () -> DiagnosticDetails.failure(failure));
    }

    public CompletableFuture<Optional<byte[]>> requestPreview(
            String key, Source source) {
        if (disposed) throw new IllegalStateException("Preview loader is closed");
        byte[] cached = previewBytes.get(key);
        if (cached != null) {
            return publishPreview(Optional.of(cached.clone()));
        }

        CompletableFuture<Optional<byte[]>> publication;
        boolean start;
        synchronized (previewInFlight) {
            publication = previewInFlight.get(key);
            start = publication == null;
            if (start) {
                publication = new CompletableFuture<>();
                previewInFlight.put(key, publication);
            }
        }
        CompletableFuture<Optional<byte[]>> shared = publication;
        if (!start) {
            return shared.thenApply(bytes -> bytes.map(byte[]::clone));
        }

                CompletableFuture.supplyAsync(() -> {
                    try {
                        return Objects.requireNonNull(source.get(), "preview source result")
                                .map(byte[]::clone);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        diagnose(interrupted);
                        return Optional.<byte[]>empty();
                    } catch (Exception failure) {
                        diagnose(failure);
                        return Optional.<byte[]>empty();
                    }
                }, worker)
                .whenComplete((bytes, failure) -> onClient(() -> {
                    previewInFlight.remove(key, shared);
                    if (disposed) {
                        shared.complete(Optional.empty());
                        return;
                    }
                    Optional<byte[]> result = failure == null && bytes != null
                            ? bytes.map(byte[]::clone)
                            : Optional.empty();
                    if (!staleCatalogPreview(key)) {
                        result.ifPresent(value -> previewBytes.put(key, value.clone()));
                    }
                    shared.complete(result.map(byte[]::clone));
                }));
        return shared.thenApply(bytes -> bytes.map(byte[]::clone));
    }

    public void invalidateCatalogPreviews() {
        catalogPreviewEpoch++;
        previewBytes.keySet().removeIf(key -> key.startsWith("catalog:"));
        invalidateProviderPosters();
    }

    private boolean staleCatalogPreview(String key) {
        return key.startsWith("catalog:")
                && !key.startsWith("catalog:" + catalogPreviewEpoch + ":");
    }

    public CompletableFuture<Optional<byte[]>> publishPreview(Optional<byte[]> bytes) {
        CompletableFuture<Optional<byte[]>> publication = new CompletableFuture<>();
        onClient(() -> publication.complete(bytes.map(byte[]::clone)));
        return publication;
    }

}
