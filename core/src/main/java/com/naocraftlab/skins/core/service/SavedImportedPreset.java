package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.service.AssetStorePort.Asset;
import java.util.Objects;


public record SavedImportedPreset(
        AccountState state,
        AppearancePreset preset,
        SkinAsset asset,
        Asset storedAsset) {
    public SavedImportedPreset {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(storedAsset, "storedAsset");
    }
}
