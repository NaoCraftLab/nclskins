package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

record CatalogSelection(
        SkinCatalogSource.SkinDescriptor skin,
        Optional<CatalogOrigin> origin,
        Map<SkinVariant, byte[]> variants,
        Map<SkinVariant, PresetEditorModel.ReusableCatalogVariant> reusableVariants,
        SkinVariant initialVariant, Optional<CatalogMaterialization.FrozenCatalogSelection> frozen) {
    CatalogSelection {
        Objects.requireNonNull(skin, "skin");
        origin = Objects.requireNonNull(origin, "origin");
        variants = Map.copyOf(Objects.requireNonNull(variants, "variants"));
        reusableVariants = Map.copyOf(
                Objects.requireNonNull(reusableVariants, "reusableVariants"));
        if (origin.isPresent() == !reusableVariants.isEmpty()) {
            throw new IllegalArgumentException(
                    "catalog selection must be external or reusable");
        }
        Objects.requireNonNull(initialVariant, "initialVariant");
    }
}
