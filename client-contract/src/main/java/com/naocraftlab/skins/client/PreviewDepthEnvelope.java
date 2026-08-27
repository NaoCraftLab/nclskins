package com.naocraftlab.skins.client;

import java.util.Objects;

public final class PreviewDepthEnvelope {
    public static final float UPSTREAM_MINIMUM = 1000.0F;
    public static final float MAXIMUM = 32768.0F;
    private static final float BODY_RADIUS = 0.6F;
    private static final float CAPE_DEPTH = 0.75F;
    private static final float ELYTRA_DEPTH = 1.4F;
    private static final float MARGIN = 16.0F;

    private PreviewDepthEnvelope() {
    }

    public static float forRequest(
            PreviewRenderer.PreviewIntent intent,
            float renderedScale,
            float pitchDegrees,
            PreviewRenderer.CapeMode capeMode) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(capeMode, "capeMode");
        if (!Float.isFinite(renderedScale) || renderedScale <= 0.0F
                || !Float.isFinite(pitchDegrees)) {
            throw new IllegalArgumentException("Depth inputs must be finite and positive");
        }
        if (intent != PreviewRenderer.PreviewIntent.EDITOR_DRAFT) {
            return 0.0F;
        }
        float pitch = (float) Math.toRadians(pitchDegrees);
        float bodyDepth = CenteredPlayerPreviewGeometry.MODEL_HEIGHT * 0.5F
                * Math.abs((float) Math.sin(pitch))
                + BODY_RADIUS * Math.abs((float) Math.cos(pitch));
        float attachmentDepth = switch (capeMode) {
            case OFF -> 0.0F;
            case CAPE -> CAPE_DEPTH;
            case ELYTRA -> ELYTRA_DEPTH;
        };
        return Math.max(UPSTREAM_MINIMUM, Math.min(MAXIMUM,
                renderedScale * (bodyDepth + attachmentDepth) + MARGIN));
    }
}
