package com.naocraftlab.skins.client;

import java.util.Objects;

public interface BackEquipmentPreviewRenderer<C> {
    void render(C graphicsContext, Request request);

    enum Mode {
        CAPE,
        ELYTRA
    }

    record Request(
            TextureRegistry.TextureHandle texture,
            Mode mode,
            int left,
            int top,
            int width,
            int height) {
        public Request {
            Objects.requireNonNull(texture, "texture");
            Objects.requireNonNull(mode, "mode");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Invalid back-equipment preview bounds");
            }
        }
    }
}
