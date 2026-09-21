package com.naocraftlab.skins.runtime;

public record ProviderIconProjection(float u, float v, int width, int height) {
    public static ProviderIconProjection of(boolean skin, boolean overlay, int textureWidth, int textureHeight) {
        if (textureWidth <= 0 || textureHeight <= 0) throw new IllegalArgumentException("Invalid texture dimensions");
        if (skin) {
            int side = textureWidth / 8;
            return new ProviderIconProjection(textureWidth * (overlay ? 40 : 8) / 64.0F, textureWidth / 8.0F, side, side);
        }
        return new ProviderIconProjection(textureWidth / 64.0F, textureHeight / 8.0F, textureWidth * 10 / 64, textureHeight * 10 / 32);
    }
}
