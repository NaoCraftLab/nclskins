package com.naocraftlab.skins.core.model;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

public record RemoteSkin(
        String id,
        RemoteAssetState state,
        URI textureUri,
        SkinVariant variant,
        String alias) {
    public RemoteSkin {
        id = requireId(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(textureUri, "textureUri");
        Objects.requireNonNull(variant, "variant");
        alias = normalize(alias);
    }

    public Optional<String> optionalAlias() {
        return Optional.ofNullable(alias);
    }

    private static String requireId(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 256) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return trimmed;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
