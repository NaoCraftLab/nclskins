package com.naocraftlab.skins.core.provider;

import java.util.Objects;
import java.util.Optional;

public record ProviderCape(String id, String textureCacheKey, Boolean hasElytra) {
    public ProviderCape(String id, String textureCacheKey) { this(id, textureCacheKey, null); }

    public ProviderCape {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.length() > 256) {
            throw new IllegalArgumentException("Invalid cape identity");
        }
        if (textureCacheKey != null && !textureCacheKey.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid cape cache key");
        }
    }

    public Optional<String> optionalTextureCacheKey() {
        return Optional.ofNullable(textureCacheKey);
    }
}
