package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;

import java.util.Collection;
import java.util.Objects;


public record PreviewInteractionModel(
        float yawDegrees,
        float pitchDegrees,
        float scale,
        OuterLayerVisibility outerLayerVisibility,
        PreviewRenderer.CapeMode capeMode,
        boolean rotating) {
    public static final float MIN_SCALE = 0.68F;
    public static final float MAX_SCALE = 2.0F;

    public PreviewInteractionModel {
        if (!Float.isFinite(yawDegrees)
                || !Float.isFinite(pitchDegrees)
                || !Float.isFinite(scale)
                || scale < MIN_SCALE
                || scale > MAX_SCALE) {
            throw new IllegalArgumentException("invalid preview transform");
        }
        Objects.requireNonNull(capeMode, "capeMode");
        Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
    }

    private static final float INITIAL_YAW = 25.0F;

    public static PreviewInteractionModel gallery() {
        return new PreviewInteractionModel(
                INITIAL_YAW, -8.0F, 1.0F, OuterLayerVisibility.allVisible(), PreviewRenderer.CapeMode.CAPE, false);
    }

    public static PreviewInteractionModel editor(int viewportHeight, PreviewRenderer.CapeMode capeMode) {
        return new PreviewInteractionModel(
                INITIAL_YAW, -5.0F, 1.0F, OuterLayerVisibility.allVisible(), capeMode, false);
    }

    public PreviewInteractionModel beginRotate(Bounds bounds, double mouseX, double mouseY) {
        Objects.requireNonNull(bounds, "bounds");
        return withRotating(bounds.contains(mouseX, mouseY));
    }

    public PreviewInteractionModel drag(double deltaX, double deltaY) {
        if (!rotating) {
            return this;
        }
        float nextYaw = yawDegrees + (float) deltaX * 0.8F;
        float nextPitch = Math.max(-30.0F, Math.min(30.0F, pitchDegrees - (float) deltaY * 0.6F));
        return new PreviewInteractionModel(
                nextYaw, nextPitch, scale, outerLayerVisibility, capeMode, true);
    }

    public PreviewInteractionModel endRotate() {
        return withRotating(false);
    }

    public PreviewInteractionModel scroll(
            Bounds bounds, double mouseX, double mouseY, double verticalAmount) {
        Objects.requireNonNull(bounds, "bounds");
        if (!bounds.contains(mouseX, mouseY) || verticalAmount == 0.0) {
            return this;
        }
        float nextScale = clampScale(scale + (float) verticalAmount * 0.08F);
        return new PreviewInteractionModel(
                yawDegrees, pitchDegrees, nextScale, outerLayerVisibility, capeMode, rotating);
    }

    public PreviewInteractionModel toggleOuterLayer() {
        return new PreviewInteractionModel(
                yawDegrees,
                pitchDegrees,
                scale,
                outerLayerVisibility.equals(OuterLayerVisibility.allVisible())
                        ? OuterLayerVisibility.noneVisible()
                        : OuterLayerVisibility.allVisible(),
                capeMode,
                rotating);
    }

    public PreviewInteractionModel toggleOuterLayerPart(OuterLayerPart part) {
        return withOuterLayerVisibility(outerLayerVisibility.toggle(part));
    }

    public PreviewInteractionModel toggleOuterLayerGroup(Collection<OuterLayerPart> parts) {
        return withOuterLayerVisibility(outerLayerVisibility.toggleGroup(parts));
    }

    public PreviewInteractionModel withOuterLayerVisibility(OuterLayerVisibility visibility) {
        Objects.requireNonNull(visibility, "visibility");
        if (outerLayerVisibility.equals(visibility)) {
            return this;
        }
        return new PreviewInteractionModel(
                yawDegrees, pitchDegrees, scale, visibility, capeMode, rotating);
    }

    public PreviewInteractionModel cycleCapeMode(boolean capeSelected) {
        if (!capeSelected) {
            return this;
        }
        PreviewRenderer.CapeMode next = capeMode == PreviewRenderer.CapeMode.ELYTRA
                ? PreviewRenderer.CapeMode.CAPE
                : PreviewRenderer.CapeMode.ELYTRA;
        return new PreviewInteractionModel(
                yawDegrees, pitchDegrees, scale, outerLayerVisibility, next, rotating);
    }

    public PreviewInteractionModel withCapeMode(PreviewRenderer.CapeMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (capeMode == mode) {
            return this;
        }
        return new PreviewInteractionModel(
                yawDegrees, pitchDegrees, scale, outerLayerVisibility, mode, rotating);
    }

    private PreviewInteractionModel withRotating(boolean value) {
        if (rotating == value) {
            return this;
        }
        return new PreviewInteractionModel(
                yawDegrees, pitchDegrees, scale, outerLayerVisibility, capeMode, value);
    }

    private static float clampScale(float value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }
}
