package com.naocraftlab.skins.core.png;

import com.naocraftlab.skins.core.model.SkinVariant;

import java.util.Objects;


public record NormalizedSkin(byte[] pngBytes, SkinVariant detectedVariant) {
    public NormalizedSkin {
        pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
        Objects.requireNonNull(detectedVariant, "detectedVariant");
    }

    @Override
    public byte[] pngBytes() {
        return pngBytes.clone();
    }
}
