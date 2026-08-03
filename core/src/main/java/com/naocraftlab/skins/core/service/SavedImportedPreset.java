package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.storage.StoredAsset;
import java.util.Objects;


public record SavedImportedPreset(
        AccountState state,
        AppearancePreset preset,
        SkinAsset asset,
        StoredAsset storedAsset) {
    public SavedImportedPreset {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(storedAsset, "storedAsset");
    }
}
