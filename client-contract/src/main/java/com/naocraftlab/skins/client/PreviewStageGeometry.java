package com.naocraftlab.skins.client;

public final class PreviewStageGeometry {
    private PreviewStageGeometry() {
    }

    public static float modelOffsetX(PreviewRenderer.PreviewRequest request, float renderedScale) {
        if (!Float.isFinite(renderedScale) || renderedScale <= 0.0F) {
            throw new IllegalArgumentException("Rendered scale must be finite and positive");
        }
        float anchorCenter = request.left() + request.width() / 2.0F;
        float stageCenter = request.stageLeft() + request.stageWidth() / 2.0F;
        return (anchorCenter - stageCenter) / renderedScale;
    }

    public static float modelOffsetY(PreviewRenderer.PreviewRequest request, float renderedScale) {
        if (!Float.isFinite(renderedScale) || renderedScale <= 0.0F) {
            throw new IllegalArgumentException("Rendered scale must be finite and positive");
        }
        float anchorCenter = request.top() + request.height() / 2.0F;
        float stageCenter = request.stageTop() + request.stageHeight() / 2.0F;
        return (anchorCenter - stageCenter) / renderedScale;
    }
}
