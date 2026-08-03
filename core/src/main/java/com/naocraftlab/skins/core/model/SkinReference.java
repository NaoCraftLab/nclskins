package com.naocraftlab.skins.core.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


public record SkinReference(Kind kind, UUID assetId) {
    public enum Kind {
        ACCOUNT_DEFAULT,
        ASSET
    }

    public SkinReference {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.ASSET && assetId == null) {
            throw new IllegalArgumentException("assetId is required for ASSET");
        }
        if (kind == Kind.ACCOUNT_DEFAULT && assetId != null) {
            throw new IllegalArgumentException("assetId must be absent for ACCOUNT_DEFAULT");
        }
    }

    public static SkinReference accountDefault() {
        return new SkinReference(Kind.ACCOUNT_DEFAULT, null);
    }

    public static SkinReference asset(UUID assetId) {
        return new SkinReference(Kind.ASSET, Objects.requireNonNull(assetId, "assetId"));
    }

    public Optional<UUID> optionalAssetId() {
        return Optional.ofNullable(assetId);
    }
}
