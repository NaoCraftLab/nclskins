package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.service.ProviderTextureStore;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ProviderTextureStorageAdapter implements ProviderTextureStore {
    private final NclSkinsStorage storage;
    private final TextureCache textures;
    public ProviderTextureStorageAdapter(NclSkinsStorage storage, TextureCache textures) {
        this.storage = java.util.Objects.requireNonNull(storage);
        this.textures = java.util.Objects.requireNonNull(textures);
    }
    public byte[] load(URI texture) throws IOException { return textures.read(textures.get(texture)); }
    public Optional<byte[]> readIfCached(URI texture) throws IOException { return textures.readIfCached(texture); }
    public Optional<byte[]> readIfCached(String key) throws IOException { return textures.readIfCached(key); }
    public String cacheKey(URI texture) { return TextureCache.cacheKey(texture); }
    public String storeObservedCape(PngValidator.CapePng cape) throws IOException, PngValidationException { return textures.storeObservedCape(cape); }
    public String storeObservedPreparedCape(PreparedCapeAsset cape) throws IOException {
        return storage.storeObservedPreparedCape(cape);
    }
    public Optional<PreparedCapeAsset> readObservedPreparedCape(String visualSha256) throws IOException {
        return storage.readObservedPreparedCape(visualSha256);
    }
    @Override public Optional<PreparedCapeAsset> readObservedPreparedCapeThumbnail(String visualSha256) throws IOException {
        return storage.readObservedPreparedCapeThumbnail(visualSha256);
    }
    @Override public List<ObservedPreparedCape> listObservedPreparedCapes() throws IOException {
        return storage.listObservedPreparedCapes();
    }
    @Override public boolean removeObservedPreparedCape(String visualSha256) throws IOException {
        return storage.removeObservedPreparedCape(visualSha256);
    }
    @Override public Optional<PreparedCapeAsset> readLocalPreparedCape(UUID account, String descriptorHash)
            throws IOException {
        return storage.readPreparedCapeAsset(account, descriptorHash);
    }
    @Override public Optional<PreparedCapeAsset> readLocalPreparedCapeThumbnail(UUID account, String descriptorHash)
            throws IOException {
        return storage.readPreparedCapeThumbnail(account, descriptorHash);
    }
    public Optional<byte[]> readLocalCape(UUID account, String key) throws IOException, PngValidationException {
        if (Files.exists(storage.capeAssetPath(account, key).resolveSibling(key + ".cape"))) {
            return Optional.empty();
        }
        return Files.isRegularFile(storage.capeAssetPath(account, key))
                ? Optional.of(storage.readCapeAsset(account, key)) : Optional.empty();
    }
}
