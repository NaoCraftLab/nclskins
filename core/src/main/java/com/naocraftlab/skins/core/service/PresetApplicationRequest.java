package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.SkinReference;
import java.util.Objects;

public record PresetApplicationRequest(AppearancePreset preset, ResolvedSkinAsset resolvedSkin) {
    public PresetApplicationRequest {
        Objects.requireNonNull(preset, "preset");
        if (preset.skin().kind() == SkinReference.Kind.ASSET) {
            if (resolvedSkin == null || !preset.skin().assetId().equals(resolvedSkin.assetId())) {
                throw new IllegalArgumentException("Preset skin must be resolved to the matching local asset");
            }
        } else if (resolvedSkin != null) {
            throw new IllegalArgumentException("ACCOUNT_DEFAULT must not carry resolved skin bytes");
        }
    }
}
