package com.naocraftlab.skins.runtime;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import com.naocraftlab.skins.client.ClientExecutor;

public interface PreviewAssetSource {
    Optional<byte[]> loadProviderTexture(String cacheKey, boolean skin) throws Exception;

    default Optional<ProviderCapePoster> loadProviderPoster(String cacheKey) throws Exception {
        return Optional.empty();
    }

    byte[] loadSkinPreview(UUID skinId) throws Exception;

    Optional<byte[]> loadCapePreview(String capeId) throws Exception;

    default Optional<CatalogRead.PreparedCapeLease> acquirePreparedCapePreview(String capeId)
            throws Exception {
        return Optional.empty();
    }

    default Optional<CapePreviewSource> openPreparedCapePreview(UUID accountId,
            Optional<CatalogRead.ResourceCapeSelection> resource, String capeId,
            Executor worker, ClientExecutor clientExecutor) throws Exception {
        return Optional.empty();
    }
    default Optional<CapePreviewSource> openPreparedCapePreview(UUID accountId,
            Optional<CatalogRead.ResourceCapeSelection> resource, String capeId,
            Executor worker, ClientExecutor clientExecutor, boolean thumbnail) throws Exception {
        return openPreparedCapePreview(accountId, resource, capeId, worker, clientExecutor);
    }
}
