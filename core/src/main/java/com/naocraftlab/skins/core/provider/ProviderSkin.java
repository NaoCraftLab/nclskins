package com.naocraftlab.skins.core.provider;

import com.naocraftlab.skins.core.model.SkinVariant;
import java.util.Objects;

public record ProviderSkin(String sha256, SkinVariant variant) {
    public ProviderSkin {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid skin asset hash");
        }
        Objects.requireNonNull(variant, "variant");
    }
}
