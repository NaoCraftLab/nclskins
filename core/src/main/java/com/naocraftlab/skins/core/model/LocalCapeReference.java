package com.naocraftlab.skins.core.model;

import java.util.Objects;
import java.util.UUID;

public record LocalCapeReference(UUID entryId, String sha256, boolean hasElytra) {
    public LocalCapeReference {
        Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid cape asset hash");
        }
    }
}
