package com.naocraftlab.skins.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface PreviewAssets extends AutoCloseable {
    long catalogEpoch();
    void installWarmed(Map<String, byte[]> warmed, boolean replaceResources);
    CompletableFuture<Optional<byte[]>> requestPreview(String key, Source source);
    void invalidateCatalogPreviews();
    CompletableFuture<Optional<byte[]>> publishPreview(Optional<byte[]> bytes);
    void close();

    interface Source { Optional<byte[]> get() throws Exception; }
}
