package com.naocraftlab.skins.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface PreviewAssets extends AutoCloseable {
    long catalogEpoch();
    void installWarmed(Map<String, byte[]> warmed, boolean replaceResources);
    CompletableFuture<Optional<byte[]>> requestPreview(String key, Source source);
    default CompletableFuture<Optional<CapeResult>> requestCapePreview(String key, CapeSource source) {
        return requestPreview(key, () -> source.get().map(CapeResult::bytes))
                .thenApply(bytes -> bytes.map(value -> new CapeResult(value, Optional.empty())));
    }
    default Optional<String> cachedCapeVisualIdentity(String key) { return Optional.empty(); }
    default Optional<CapeComparison> cachedCapeComparison(String key) { return Optional.empty(); }
    void rememberCapeComparison(String key, CapeComparison comparison);
    record CapeComparison(Optional<String> key) {
        public CapeComparison { java.util.Objects.requireNonNull(key); }
    }
    record CapeResult(byte[] bytes, Optional<String> visualSha256, CapeComparison comparison) {
        public CapeResult(byte[] bytes, Optional<String> visualSha256) {
            this(bytes, visualSha256, new CapeComparison(Optional.empty()));
        }
        public CapeResult {
            bytes = java.util.Objects.requireNonNull(bytes, "bytes").clone();
            java.util.Objects.requireNonNull(visualSha256, "visualSha256");
            java.util.Objects.requireNonNull(comparison, "comparison");
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    interface CapeSource {
        Optional<CapeResult> get() throws Exception;
        default CompletableFuture<Optional<CapeResult>> getAsync(java.util.concurrent.Executor worker) throws Exception {
            return CompletableFuture.completedFuture(get());
        }
    }
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

    record SneakyResult(SneakyStatus status, byte[] atlas, String identity, boolean hasElytra, Optional<String> comparisonKey) {
        public SneakyResult(SneakyStatus status, byte[] atlas, String identity, boolean hasElytra) {
            this(status, atlas, identity, hasElytra, Optional.empty());
        }
        public SneakyResult {
            java.util.Objects.requireNonNull(comparisonKey);
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
