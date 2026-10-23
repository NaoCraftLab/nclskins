package com.naocraftlab.skins.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface PreviewAssets extends AutoCloseable {
    long catalogEpoch();
    void installWarmed(Map<String, byte[]> warmed, boolean replaceResources);
    CompletableFuture<Optional<byte[]>> requestPreview(String key, Source source);
    void installWarmedProviderPosters(Map<String, ProviderCapePoster> posters);
    CompletableFuture<Optional<ProviderCapePoster>> requestProviderPoster(String key, PosterSource source);
    void invalidateProviderPosters();
    void invalidateProviderPoster(String key);
    void invalidateCatalogPreviews();
    CompletableFuture<Optional<byte[]>> publishPreview(Optional<byte[]> bytes);
    SneakyLease prepareSneaky(byte[] bytes);
    void invalidateSneaky();
    void close();

    interface SneakyLease extends AutoCloseable {
        CompletableFuture<SneakyResult> result();
        void close();
    }

    enum SneakyStatus { VALUE, ABSENCE, FAILURE }

    record SneakyResult(SneakyStatus status, byte[] atlas, String identity, boolean hasElytra) {
        public SneakyResult {
            java.util.Objects.requireNonNull(status, "status");
            atlas = atlas == null ? null : atlas.clone();
            if (status == SneakyStatus.VALUE && (atlas == null || identity == null)) {
                throw new IllegalArgumentException("Missing Sneaky value");
            }
        }
        @Override public byte[] atlas() { return atlas == null ? null : atlas.clone(); }
        public static SneakyResult absence() { return new SneakyResult(SneakyStatus.ABSENCE, null, null, false); }
        public static SneakyResult failure() { return new SneakyResult(SneakyStatus.FAILURE, null, null, false); }
    }

    interface Source { Optional<byte[]> get() throws Exception; }
    interface PosterSource { Optional<ProviderCapePoster> get() throws Exception; }
}
