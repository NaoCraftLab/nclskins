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

    @Test
    void pitchedEntityTranslationKeepsTheGeometricCenterAtThePipOrigin() {
        float centerY = CenteredPlayerPreviewGeometry.modernEntityTranslationY(
                CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT);

        for (float pitchDegrees : new float[]{-30.0F, 0.0F, 30.0F}) {
            float pitchRadians = (float) Math.toRadians(pitchDegrees);
            CenteredPlayerPreviewGeometry.EntityTranslation translation =
                    CenteredPlayerPreviewGeometry.centeredEntityTranslation(
                            CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT,
                            pitchRadians);
            float rotatedCenterY = -centerY * (float) Math.cos(pitchRadians);
            float rotatedCenterZ = centerY * (float) Math.sin(pitchRadians);

            assertEquals(0.0F, translation.y() + rotatedCenterY, EPSILON);
            assertEquals(0.0F, translation.z() + rotatedCenterZ, EPSILON);
        }
    }

    @Test
    void legacyHostAnchorKeepsProjectedCenterFixedAtExtremePitchAndZoom() {
        float viewportCenter = 137.0F;
        for (float zoom : new float[]{0.68F, 1.0F, 2.0F}) {
            float scale = CenteredPlayerPreviewGeometry.fittedScale(240, zoom);
            for (float pitchDegrees : new float[]{-30.0F, 0.0F, 30.0F}) {
                float pitch = (float) Math.toRadians(pitchDegrees);
                float centerOffset = CenteredPlayerPreviewGeometry.modernEntityTranslationY(1.8F);
                float anchor = CenteredPlayerPreviewGeometry.legacyEntityAnchorY(
                        viewportCenter, scale, 1.8F, pitch);
                float projectedCenter = anchor - scale * centerOffset * (float) Math.cos(pitch);
                assertEquals(viewportCenter, projectedCenter, EPSILON);
            }
        }
    }
}
