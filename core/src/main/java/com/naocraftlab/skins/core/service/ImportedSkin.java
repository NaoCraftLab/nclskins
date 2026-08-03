package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.storage.StoredAsset;
import java.util.Objects;

public record ImportedSkin(AccountState state, SkinAsset asset, StoredAsset storedAsset) {
    public ImportedSkin {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(storedAsset, "storedAsset");
    }
}
