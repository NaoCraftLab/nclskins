package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.AccountUiPreferences;

import java.util.List;
import java.util.Map;
import java.util.Objects;

record AddSourceData(
        AccountUiPreferences preferences,
        List<SkinCatalogSource.CollectionDescriptor> collections,
        Map<CatalogRead.CatalogVariant, SkinFeatureEvidence> featureEvidence) {
    AddSourceData {
        Objects.requireNonNull(preferences, "preferences");
        collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
        featureEvidence = Map.copyOf(Objects.requireNonNull(
                featureEvidence, "featureEvidence"));
    }
}
