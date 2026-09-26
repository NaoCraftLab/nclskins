package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;

public interface ProviderTextureStore {
    byte[] load(URI texture) throws IOException;
    Optional<byte[]> readIfCached(URI texture) throws IOException;
    Optional<byte[]> readIfCached(String key) throws IOException;
    String cacheKey(URI texture);
    String storeObservedCape(PngValidator.CapePng cape) throws IOException, PngValidationException;
    Optional<byte[]> readLocalCape(UUID account, String key) throws IOException, PngValidationException;
}
