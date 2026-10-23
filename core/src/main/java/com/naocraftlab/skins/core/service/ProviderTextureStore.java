package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ProviderTextureStore {
    byte[] load(URI texture) throws IOException;
    Optional<byte[]> readIfCached(URI texture) throws IOException;
    Optional<byte[]> readIfCached(String key) throws IOException;
    String cacheKey(URI texture);
    String storeObservedCape(PngValidator.CapePng cape) throws IOException, PngValidationException;
    default String storeObservedPreparedCape(PreparedCapeAsset cape) throws IOException {
        throw new UnsupportedOperationException("Prepared cape cache unavailable");
    }
    default Optional<PreparedCapeAsset> readObservedPreparedCape(String visualSha256) throws IOException {
        return Optional.empty();
    }
    default Optional<PreparedCapeAsset> readObservedPreparedCapeThumbnail(String visualSha256) throws IOException {
        return readObservedPreparedCape(visualSha256);
    }
    default List<ObservedPreparedCape> listObservedPreparedCapes() throws IOException {
        return List.of();
    }
    default boolean removeObservedPreparedCape(String visualSha256) throws IOException {
        throw new UnsupportedOperationException("Prepared cape cache eviction unavailable");
    }
    record ObservedPreparedCape(String visualSha256, Path backing, long storedBytes,
            long lastModifiedMillis) {
        public ObservedPreparedCape {
            if (visualSha256 == null || !visualSha256.matches("[0-9a-f]{64}")
                    || backing == null || storedBytes < 0 || lastModifiedMillis < 0) {
                throw new IllegalArgumentException("Invalid observed prepared cape metadata");
            }
        }
    }
    default Optional<PreparedCapeAsset> readLocalPreparedCape(UUID account, String descriptorHash)
            throws IOException {
        return Optional.empty();
    }
    default Optional<PreparedCapeAsset> readLocalPreparedCapeThumbnail(UUID account, String descriptorHash)
            throws IOException {
        return readLocalPreparedCape(account, descriptorHash);
    }
    Optional<byte[]> readLocalCape(UUID account, String key) throws IOException, PngValidationException;
}
