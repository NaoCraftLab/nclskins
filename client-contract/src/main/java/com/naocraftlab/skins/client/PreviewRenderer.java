package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.Optional;


public interface PreviewRenderer<C> {
    void render(C graphicsContext, PreviewRequest request);

    enum PreviewIntent {
        EDITOR_DRAFT,
        CURRENT_APPEARANCE,
        ASSET_THUMBNAIL
    }

    enum CapeMode {
        OFF,
        CAPE,
        ELYTRA
    }

    record PreviewAppearance(
            TextureRegistry.TextureHandle skin,
            SkinModel model,
            Optional<TextureRegistry.TextureHandle> cape,
            CapeMode capeMode,
            OuterLayerVisibility outerLayerVisibility) {
        public PreviewAppearance {
            Objects.requireNonNull(skin, "skin");
            Objects.requireNonNull(model, "model");
            cape = Objects.requireNonNull(cape, "cape");
            Objects.requireNonNull(capeMode, "capeMode");
            Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
            if (capeMode != CapeMode.OFF && cape.isEmpty()) {
                throw new IllegalArgumentException("Cape and elytra preview require a selected cape");
            }
        }

        public PreviewAppearance(
                TextureRegistry.TextureHandle skin,
                SkinModel model,
                Optional<TextureRegistry.TextureHandle> cape,
                CapeMode capeMode,
                boolean outerLayerVisible) {
            this(
                    skin,
                    model,
                    cape,
                    capeMode,
                    outerLayerVisible
                            ? OuterLayerVisibility.allVisible()
                            : OuterLayerVisibility.noneVisible());
        }
    }

    record PreviewRequest(
            PreviewAppearance appearance,
            int left,
            int top,
            int width,
            int height,
            float yawDegrees,
            float pitchDegrees,
            float scale,
            PreviewIntent intent) {
        public PreviewRequest(
                PreviewAppearance appearance,
                int left,
                int top,
                int width,
                int height,
                float yawDegrees,
                float pitchDegrees,
                float scale) {
            this(
                    appearance,
                    left,
                    top,
                    width,
                    height,
                    yawDegrees,
                    pitchDegrees,
                    scale,
                    PreviewIntent.ASSET_THUMBNAIL);
        }

        public PreviewRequest {
            Objects.requireNonNull(appearance, "appearance");
            Objects.requireNonNull(intent, "intent");
            if (width <= 0 || height <= 0 || !Float.isFinite(scale) || scale <= 0.0F) {
                throw new IllegalArgumentException("Invalid preview bounds or scale");
            }
        }
    }
}
