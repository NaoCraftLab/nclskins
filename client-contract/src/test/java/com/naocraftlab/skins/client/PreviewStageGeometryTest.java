package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewStageGeometryTest {
    @Test
    void fullScreenStageRetainsTheLeftAnchorAcrossWidthsAndZooms() {
        PreviewRenderer.PreviewAppearance appearance = new PreviewRenderer.PreviewAppearance(
                new TextureRegistry.TextureHandle("nclskins:test", 64, 64),
                SkinModel.CLASSIC,
                Optional.empty(),
                PreviewRenderer.CapeMode.OFF,
                true);
        for (int screenWidth : new int[]{240, 320, 854, 1600}) {
            int anchorWidth = Math.max(120, screenWidth - 276);
            for (float zoom : new float[]{0.68F, 1.0F, 2.0F}) {
                PreviewRenderer.PreviewRequest request = new PreviewRenderer.PreviewRequest(
                        appearance, 0, 0, anchorWidth, 240, 0.0F, 30.0F, zoom,
                        PreviewRenderer.PreviewIntent.EDITOR_DRAFT,
                        0, 0, screenWidth, 240);
                float scale = CenteredPlayerPreviewGeometry.fittedScale(240, zoom);
                float reconstructedCenter = screenWidth / 2.0F
                        + PreviewStageGeometry.modelOffsetX(request, scale) * scale;
                assertEquals(anchorWidth / 2.0F, reconstructedCenter, 0.0001F);
                assertEquals(0.0F, PreviewStageGeometry.modelOffsetY(request, scale));
            }
        }
    }
}
