package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CenteredPlayerPreviewGeometryTest {
    private static final float EPSILON = 0.0001F;

    @Test
    void fitUsesOnlyViewportHeight() {
        CenteredPlayerPreviewGeometry.Layout narrow =
                CenteredPlayerPreviewGeometry.fit(0, 20, 120, 200, 1.0F);
        CenteredPlayerPreviewGeometry.Layout wide =
                CenteredPlayerPreviewGeometry.fit(0, 20, 640, 200, 1.0F);

        assertEquals(narrow.scale(), wide.scale(), EPSILON);
        assertEquals(0.97F * 200.0F / 2.125F, narrow.scale(), EPSILON);
    }

    @Test
    void zoomKeepsLegacyModelCenterAtViewportCenter() {
        float viewportCenterY = 147.0F;
        float entityHeight = 1.8F;

        for (float zoom : new float[]{0.68F, 1.0F, 2.0F}) {
            float scale = CenteredPlayerPreviewGeometry.fittedScale(220, zoom);
            float anchor = CenteredPlayerPreviewGeometry.legacyEntityAnchorY(
                    viewportCenterY, scale, entityHeight);
            float reconstructedCenter = anchor
                    - scale * CenteredPlayerPreviewGeometry.modernEntityTranslationY(entityHeight);

            assertEquals(viewportCenterY, reconstructedCenter, EPSILON);
        }
    }

    @Test
    void legacyAnchorCompensationEqualsModernModelTranslation() {
        float viewportCenterY = 100.0F;
        float renderedEntityScale = 53.0F;
        float entityHeight = 1.8F;

        float legacyAnchor = CenteredPlayerPreviewGeometry.legacyEntityAnchorY(
                viewportCenterY, renderedEntityScale, entityHeight);
        float modernPixelTranslation = renderedEntityScale
                * CenteredPlayerPreviewGeometry.modernEntityTranslationY(entityHeight);

        assertEquals(modernPixelTranslation, legacyAnchor - viewportCenterY, EPSILON);
    }
}
