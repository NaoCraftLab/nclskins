package com.naocraftlab.skins.runtime;

import java.util.Optional;
import java.util.UUID;

public interface PreviewAssetSource {
    Optional<byte[]> loadProviderTexture(String cacheKey, boolean skin) throws Exception;

    byte[] loadSkinPreview(UUID skinId) throws Exception;

    Optional<byte[]> loadCapePreview(String capeId) throws Exception;
}
