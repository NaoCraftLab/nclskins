package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.Optional;


@FunctionalInterface
public interface CurrentPlayerAppearanceSource {
    PlayerAppearance currentPlayerAppearance();

    record PlayerAppearance(
            TextureRegistry.TextureHandle skin,
            SkinModel model,
            Optional<TextureRegistry.TextureHandle> cape) {
        public PlayerAppearance {
            Objects.requireNonNull(skin, "skin");
            Objects.requireNonNull(model, "model");
            cape = Objects.requireNonNull(cape, "cape");
        }
    }
}
