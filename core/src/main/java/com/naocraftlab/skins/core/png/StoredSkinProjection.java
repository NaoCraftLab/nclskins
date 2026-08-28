package com.naocraftlab.skins.core.png;

import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.util.Objects;


public record StoredSkinProjection(
        byte[] pngBytes,
        SkinVariant detectedVariant,
        SkinFeatureEvidence featureEvidence) {
    public StoredSkinProjection {
        pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
        Objects.requireNonNull(detectedVariant, "detectedVariant");
        Objects.requireNonNull(featureEvidence, "featureEvidence");
    }

    @Override
    public byte[] pngBytes() {
        return pngBytes.clone();
    }
}
