package com.naocraftlab.skins.client;


public final class LegacyPreviewDepth {
    private static final float MODEL_DEPTH_RADIUS = 2.0F;
    private static final float GUI_SAFETY_MARGIN = 16.0F;

    private LegacyPreviewDepth() {
    }


    public static float required(float modelScale) {
        validatePositiveFinite(modelScale, "modelScale");
        return (float) Math.ceil(modelScale * MODEL_DEPTH_RADIUS + GUI_SAFETY_MARGIN);
    }


    public static float additional(float modelScale, float builtInDepth) {
        validatePositiveFinite(modelScale, "modelScale");
        if (!Float.isFinite(builtInDepth) || builtInDepth < 0.0F) {
            throw new IllegalArgumentException("builtInDepth must be finite and non-negative");
        }
        return Math.max(0.0F, required(modelScale) - builtInDepth);
    }

    private static void validatePositiveFinite(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0F) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }
}
