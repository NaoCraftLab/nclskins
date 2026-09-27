package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import java.util.Map;
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
    private long catalogPreviewEpoch;
    private volatile boolean disposed;

    PreviewAssetLoader(ClientExecutor clientExecutor, Executor worker, DiagnosticSink diagnostics) {
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public long catalogEpoch() { return catalogPreviewEpoch; }

    public void installWarmed(Map<String, byte[]> warmed, boolean replaceResources) {
        if (disposed) return;
        if (replaceResources) previewBytes.keySet().removeIf(key -> key.startsWith("resource:cape:"));
        warmed.forEach((key, bytes) -> previewBytes.put(key, bytes.clone()));
    }

    @Override
    public void close() {
        disposed = true;
        previewInFlight.values().forEach(future -> future.complete(Optional.empty()));
        previewInFlight.clear();
        previewBytes.clear();
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
