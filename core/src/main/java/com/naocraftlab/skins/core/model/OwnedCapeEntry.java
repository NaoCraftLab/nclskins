package com.naocraftlab.skins.core.model;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;


public record OwnedCapeEntry(
        String id,
        String alias,
        RemoteAssetState state,
        String textureCacheKey) {
    private static final Pattern CACHE_KEY = Pattern.compile("[0-9a-f]{64}");

    public OwnedCapeEntry {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty() || id.length() > 256) {
            throw new IllegalArgumentException("cape id is invalid");
        }
        alias = alias == null || alias.isBlank() ? null : alias.trim();
        if (alias != null && alias.length() > 128) {
            throw new IllegalArgumentException("cape alias is invalid");
        }
        Objects.requireNonNull(state, "state");
        if (textureCacheKey != null && !CACHE_KEY.matcher(textureCacheKey).matches()) {
            throw new IllegalArgumentException("texture cache key is invalid");
        }
    }

    public Optional<String> optionalAlias() {
        return Optional.ofNullable(alias);
    }

    public Optional<String> optionalTextureCacheKey() {
        return Optional.ofNullable(textureCacheKey);
    }

    public OwnedCapeEntry withTextureCacheKey(String cacheKey) {
        return new OwnedCapeEntry(id, alias, state, Objects.requireNonNull(cacheKey, "cacheKey"));
    }
}
