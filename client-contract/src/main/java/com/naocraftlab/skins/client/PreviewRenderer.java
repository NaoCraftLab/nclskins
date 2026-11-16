package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.Optional;


public interface PreviewRenderer<C> {
    void render(C graphicsContext, PreviewRequest request);

    default PreviewAccessories accessories(PreviewRequest request) {
        return PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE);
    }

    default void observeAccessories(java.util.function.Consumer<PreviewAccessories> observer) {}

    interface ModelFitPreparation extends AutoCloseable {
        boolean current();
        default boolean ownerCurrent() { return current(); }
        java.util.concurrent.CompletableFuture<ModelFitMeasurement> measure();
        void complete(ModelFitMeasurement fit);
        @Override void close();
    }

    record ModelFitMeasurement(double referenceHeight, double referenceCenterX, double referenceCenterY,
            double referenceCenterZ, PreviewModelNormalization.Fit fit) {
        public ModelFitMeasurement {
            Objects.requireNonNull(fit, "fit");
            if (!Double.isFinite(referenceHeight) || referenceHeight <= 0
                    || !Double.isFinite(referenceCenterX) || !Double.isFinite(referenceCenterY) || !Double.isFinite(referenceCenterZ)
                    || fit.representation() != PreviewModelNormalization.Representation.FULL
                    || fit.normalization() != Math.min(1.0, referenceHeight / fit.bodyHeight()))
                throw new IllegalArgumentException("Invalid measured full fit");
        }
    }

    default boolean modelFitApplicable() { return true; }

    default Optional<ModelFitPreparation> prepareModelFit(String source, PreviewAppearance appearance) {
        return Optional.empty();
    }

    default void suppressModelFit(String source) {}

    enum BodyStatus { ORDINARY, PENDING, READY, FAILED, BLOCKED, TIMED_OUT }
    enum DisplayKind { UNCONFIRMED, ORDINARY, CPM }

    default void prepareImage(Object source, long startedNanos) {}
    default void prepareImage(Object source, long startedNanos, boolean inputsReady) { prepareImage(source, startedNanos); }
    default void prepareImage(Object source, long startedNanos, boolean inputsReady, boolean indicator) {
        prepareImage(source, startedNanos, inputsReady);
    }
    default void prepareBody(String source, boolean cpm) {}
    default void prepareBody(String source, String demand, boolean cpm) { prepareBody(source, cpm); }
    default boolean prepareGalleryBody(String source, String demand, boolean cpm, java.util.function.BooleanSupplier candidateCurrent) {
        return prepareGalleryBody(source, cpm, candidateCurrent);
    }
    default boolean prepareGalleryBody(String source, boolean cpm, java.util.function.BooleanSupplier candidateCurrent) { return false; }
    default boolean prepareGalleryImage(String source, String demand, boolean cpm,
            java.util.function.BooleanSupplier candidateCurrent, java.util.function.BooleanSupplier lifetime) {
        return prepareGalleryImage(source, cpm, candidateCurrent, lifetime);
    }
    default boolean prepareGalleryImage(String source, boolean cpm, java.util.function.BooleanSupplier candidateCurrent) { return false; }
    default boolean prepareGalleryImage(String source, boolean cpm, java.util.function.BooleanSupplier candidateCurrent,
            java.util.function.BooleanSupplier lifetime) { return prepareGalleryImage(source, cpm, candidateCurrent); }
    default void confirmGalleryBody(String source) {}
    default PreviewAccessories galleryAccessories(String source, PreviewRequest request, java.util.function.BooleanSupplier inputCurrent) {
        return PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE);
    }
    default java.util.Set<TextureRegistry.TextureHandle> retainedTextures() { return java.util.Set.of(); }
    default void renderRetained(C graphicsContext, String source) {}
    default void transformRetained(String source, float yawDegrees, float pitchDegrees, float scale) {}
    default void renderGallery(C graphicsContext, String source, PreviewRequest request) {
        throw new UnsupportedOperationException("Gallery retained display is not implemented by this renderer");
    }
    default void observeBody(java.util.function.Consumer<BodyStatus> observer) {}
    default void retireBody() {}
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
            boolean capeGlint, boolean nativeAccessories, TextureUseValidity capeValidity,
            CapeAssetOwner capeAssetOwner) {
        public PreviewAppearance(TextureRegistry.TextureHandle skin, SkinModel model,
                Optional<TextureRegistry.TextureHandle> cape, CapeMode capeMode,
                OuterLayerVisibility visibility, boolean capeHasElytra, boolean capeGlint,
                boolean nativeAccessories, TextureUseValidity capeValidity) {
            this(skin, model, cape, capeMode, visibility, capeHasElytra, capeGlint,
                    nativeAccessories, capeValidity, null);
        }
        public PreviewAppearance(TextureRegistry.TextureHandle skin, SkinModel model,
                Optional<TextureRegistry.TextureHandle> cape, CapeMode capeMode,
                OuterLayerVisibility visibility, boolean capeHasElytra, boolean capeGlint,
                boolean nativeAccessories) {
            this(skin, model, cape, capeMode, visibility, capeHasElytra, capeGlint,
                    nativeAccessories, TextureUseValidity.INVALID, null);
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

        public PreviewRequest hidden() {
            int offset = Math.max(left + width, stageLeft + stageWidth) + 1;
            return new PreviewRequest(appearance, left - offset, top, width, height,
                    yawDegrees, pitchDegrees, scale, intent,
                    stageLeft - offset, stageTop, stageWidth, stageHeight);
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
