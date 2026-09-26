package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.model.PersonalCapeEntry;
import java.util.Optional;
import java.util.UUID;

public interface CatalogMaterialization {
    Optional<FrozenCatalogSelection> freezeCatalogSelection(String collectionId, String skinId) throws Exception;
    record FrozenCatalogSelection(UUID accountId, String collectionId, String skinId, long generation,
            java.util.Map<SkinModel, String> sourceHashes, java.util.Map<SkinModel, UUID> personalAssets) {
        public FrozenCatalogSelection {
            java.util.Objects.requireNonNull(accountId);
            java.util.Objects.requireNonNull(collectionId);
            java.util.Objects.requireNonNull(skinId);
            sourceHashes = java.util.Map.copyOf(sourceHashes);
            personalAssets = java.util.Map.copyOf(personalAssets);
        }
    }

    byte[] loadCatalogSkin(String collectionId, String skinId, SkinModel model) throws Exception;
    Optional<UUID> reusableCatalogSkinAsset(String collectionId, String skinId, SkinModel model) throws Exception;
    PersonalCapeEntry materializeResourceCape(UUID accountId, CatalogRead.ResourceCapeSelection selection) throws Exception;
}
