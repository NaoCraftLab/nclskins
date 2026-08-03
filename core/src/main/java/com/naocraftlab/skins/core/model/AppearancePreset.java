package com.naocraftlab.skins.core.model;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public record AppearancePreset(
        UUID id,
        String name,
        SkinReference skin,
        String capeId,
        OuterLayerVisibility outerLayerVisibility,
        Instant createdAt,
        Instant updatedAt) {

    public AppearancePreset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        name = name.trim();
        if (name.isEmpty() || name.length() > 128) {
            throw new IllegalArgumentException("name must contain between 1 and 128 characters");
        }
        Objects.requireNonNull(skin, "skin");
        capeId = normalizeOptionalId(capeId);
        Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot precede createdAt");
        }
    }


    public AppearancePreset(
            UUID id,
            String name,
            SkinReference skin,
            String capeId,
            Instant createdAt,
            Instant updatedAt) {
        this(id, name, skin, capeId, OuterLayerVisibility.allVisible(), createdAt, updatedAt);
    }

    public Optional<String> optionalCapeId() {
        return Optional.ofNullable(capeId);
    }

    private static String normalizeOptionalId(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 256) {
            throw new IllegalArgumentException("capeId must be absent or contain between 1 and 256 characters");
        }
        return trimmed;
    }
}
