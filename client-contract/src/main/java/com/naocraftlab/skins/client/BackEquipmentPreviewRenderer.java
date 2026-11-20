package com.naocraftlab.skins.client;

import java.util.Objects;

public interface BackEquipmentPreviewRenderer<C> {
    void render(C graphicsContext, Request request);

    enum Mode {
        CAPE,
        ELYTRA
    }

    enum FitPolicy { LEGACY_UNION, CATALOG_CARD }

    record Request(
            TextureRegistry.TextureHandle texture,
            Mode mode,
            int left,
            int top,
            int width,
            int height, boolean capeHasElytra, FitPolicy fitPolicy) {
        public Request(TextureRegistry.TextureHandle texture, Mode mode, int left, int top, int width, int height, boolean capeHasElytra) {
            this(texture, mode, left, top, width, height, capeHasElytra, FitPolicy.LEGACY_UNION);
        }
        public Request(TextureRegistry.TextureHandle texture, Mode mode, int left, int top, int width, int height) {
            this(texture, mode, left, top, width, height, true);
        }
        public Request {
            Objects.requireNonNull(texture, "texture");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(fitPolicy, "fitPolicy");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Invalid back-equipment preview bounds");
            }
        }
    }
}
