package com.naocraftlab.skins.client;

public final class CenteredPlayerPreviewGeometry {
    public static final float MODEL_HEIGHT = 2.125F;
    public static final float FIT_PADDING = 0.97F;
    public static final float STANDING_PLAYER_HEIGHT = 1.8F;
    public static final float ENTITY_Y_OFFSET = 0.0625F;

    private CenteredPlayerPreviewGeometry() {
    }

    public static Layout fit(
            int left,
            int top,
            int width,
            int height,
            float zoom) {
        if (width <= 0 || height <= 0 || !Float.isFinite(zoom) || zoom <= 0.0F) {
            throw new IllegalArgumentException("Player preview bounds and zoom must be positive");
        }
        return new Layout(
                left + width / 2.0F,
                top + height / 2.0F,
                fittedScale(height, zoom));
    }

    public static float fittedScale(int height, float zoom) {
        if (height <= 0 || !Float.isFinite(zoom) || zoom <= 0.0F) {
            throw new IllegalArgumentException("Player preview height and zoom must be positive");
        }
        return FIT_PADDING * height / MODEL_HEIGHT * zoom;
    }

    public static float legacyEntityAnchorY(
            float viewportCenterY,
            float renderedEntityScale,
            float entityHeight) {
        if (!Float.isFinite(viewportCenterY)
                || !Float.isFinite(renderedEntityScale)
                || renderedEntityScale <= 0.0F
                || !Float.isFinite(entityHeight)
                || entityHeight <= 0.0F) {
            throw new IllegalArgumentException("Legacy preview geometry must be finite and positive");
        }
        return viewportCenterY
                + renderedEntityScale * modernEntityTranslationY(entityHeight);
    }

    public static float modernEntityTranslationY(float entityHeight) {
        if (!Float.isFinite(entityHeight) || entityHeight <= 0.0F) {
            throw new IllegalArgumentException("Entity height must be finite and positive");
        }
        return entityHeight / 2.0F + ENTITY_Y_OFFSET;
    }

    public record Layout(float centerX, float centerY, float scale) {
        public Layout {
            if (!Float.isFinite(centerX)
                    || !Float.isFinite(centerY)
                    || !Float.isFinite(scale)
                    || scale <= 0.0F) {
                throw new IllegalArgumentException("Player preview layout must be finite and positive");
            }
        }
    }
}
