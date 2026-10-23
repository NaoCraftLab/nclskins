package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.Optional;


public interface PreviewRenderer<C> {
    void render(C graphicsContext, PreviewRequest request);

    default PreviewAccessories accessories(PreviewRequest request) {
        return PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE);
    }

    default void observeAccessories(java.util.function.Consumer<PreviewAccessories> observer) {}

    enum BodyStatus { ORDINARY, PENDING, READY, FAILED, BLOCKED, TIMED_OUT }
    enum DisplayKind { UNCONFIRMED, ORDINARY, CPM }

    default void prepareBody(String source, boolean cpm) {}
    default void observeBody(java.util.function.Consumer<BodyStatus> observer) {}
    default PreviewModelFrames.Body body() { return new PreviewModelFrames.Body(BodyStatus.ORDINARY, () -> false); }

    default DisplayKind displayKind() {
        var displayed = body();
        if (displayed == null || (displayed.status() != BodyStatus.ORDINARY && displayed.status() != BodyStatus.READY)
                || !displayed.current().getAsBoolean()) return DisplayKind.UNCONFIRMED;
        return displayed.status() == BodyStatus.READY ? DisplayKind.CPM : DisplayKind.ORDINARY;
    }


    enum LiveReason { AVAILABLE, WORLD_REQUIRED, LOADING, LIVE_UNAVAILABLE }

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
            OuterLayerVisibility outerLayerVisibility, boolean capeHasElytra,
            boolean capeGlint, boolean nativeAccessories, TextureUseValidity capeValidity) {
        public PreviewAppearance(TextureRegistry.TextureHandle skin, SkinModel model,
                Optional<TextureRegistry.TextureHandle> cape, CapeMode capeMode,
                OuterLayerVisibility visibility, boolean capeHasElytra, boolean capeGlint,
                boolean nativeAccessories) {
            this(skin, model, cape, capeMode, visibility, capeHasElytra, capeGlint,
                    nativeAccessories, TextureUseValidity.INVALID);
        }
        public PreviewAppearance(TextureRegistry.TextureHandle skin, SkinModel model,
                Optional<TextureRegistry.TextureHandle> cape, CapeMode capeMode,
                OuterLayerVisibility visibility, boolean capeHasElytra, boolean capeGlint) {
            this(skin, model, cape, capeMode, visibility, capeHasElytra, capeGlint, false);
        }
        public PreviewAppearance(TextureRegistry.TextureHandle skin, SkinModel model,
                Optional<TextureRegistry.TextureHandle> cape, CapeMode capeMode,
                OuterLayerVisibility visibility, boolean capeHasElytra) {
            this(skin, model, cape, capeMode, visibility, capeHasElytra, false);
        }
        public PreviewAppearance(TextureRegistry.TextureHandle skin, SkinModel model,
                Optional<TextureRegistry.TextureHandle> cape, CapeMode capeMode, OuterLayerVisibility visibility) {
            this(skin, model, cape, capeMode, visibility, true, false);
        }
        public PreviewAppearance {
            Objects.requireNonNull(capeValidity, "capeValidity");
            Objects.requireNonNull(skin, "skin");
            Objects.requireNonNull(model, "model");
            cape = Objects.requireNonNull(cape, "cape");
            Objects.requireNonNull(capeMode, "capeMode");
            Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
            if (capeMode != CapeMode.OFF && cape.isEmpty() && !nativeAccessories) {
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
                            : OuterLayerVisibility.noneVisible(), true, false);
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
            PreviewIntent intent,
            int stageLeft,
            int stageTop,
            int stageWidth,
            int stageHeight) {
        public PreviewRequest(
                PreviewAppearance appearance,
                int left,
                int top,
                int width,
                int height,
                float yawDegrees,
                float pitchDegrees,
                float scale,
                PreviewIntent intent) {
            this(
                    appearance,
                    left,
                    top,
                    width,
                    height,
                    yawDegrees,
                    pitchDegrees,
                    scale,
                    intent,
                    left,
                    top,
                    width,
                    height);
        }

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
            if (width <= 0 || height <= 0 || stageWidth <= 0 || stageHeight <= 0
                    || !Float.isFinite(scale) || scale <= 0.0F) {
                throw new IllegalArgumentException("Invalid preview bounds or scale");
            }
        }
    }
}
