package com.naocraftlab.skins.compat.client.identifier;

import java.util.Objects;

public record CapeMaterialSnapshot(String textureLocation, boolean capeGlint,
        com.naocraftlab.skins.client.TextureUseValidity validity) {

    public CapeMaterialSnapshot {
        Objects.requireNonNull(textureLocation, "textureLocation");
        Objects.requireNonNull(validity, "validity");
    }

    public static CapeMaterialSnapshot preview(String installedLocation, String selectedLocation,
            boolean capeMode, boolean capeGlint, com.naocraftlab.skins.client.TextureUseValidity validity) {
        if (!capeMode || !capeGlint || installedLocation == null
                || !installedLocation.equals(selectedLocation)) return null;
        return new CapeMaterialSnapshot(installedLocation, true, validity);
    }

    public static CapeMaterialSnapshot world(java.util.UUID profileId, String canonicalName,
            boolean self, String installedLocation) {
        if (profileId == null || canonicalName == null || installedLocation == null) return null;
        var selected = com.naocraftlab.skins.runtime.CapeProjection.material(profileId, canonicalName, self);
        if (!selected.capeGlint() || !installedLocation.equals(selected.capeLocation())) return null;
        return new CapeMaterialSnapshot(installedLocation, true,
                com.naocraftlab.skins.runtime.CapeProjection.captureCapeTexture(
                        profileId, canonicalName, self, installedLocation));
    }

    public interface Carrier {
        CapeMaterialSnapshot nclskins$capeMaterial();
        void nclskins$setCapeMaterial(CapeMaterialSnapshot snapshot);
    }
}
