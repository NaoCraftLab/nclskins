package com.naocraftlab.skins.core.model;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

public record RemoteCape(
        String id,
        RemoteAssetState state,
        URI textureUri,
        String alias) {
    public RemoteCape {
        Objects.requireNonNull(id, "id");
        id = id.trim();
        if (id.isEmpty() || id.length() > 256) {
            throw new IllegalArgumentException("id is invalid");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(textureUri, "textureUri");
        alias = alias == null || alias.isBlank() ? null : alias.trim();
    }

    public Optional<String> optionalAlias() {
        return Optional.ofNullable(alias);
    }
}
