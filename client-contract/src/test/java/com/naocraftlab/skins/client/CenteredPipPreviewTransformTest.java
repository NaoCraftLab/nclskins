package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CenteredPipPreviewTransformTest {
    private static final float EPSILON = 0.00001F;

    @Test
    void playerYawIsAppliedOnceAroundTheGeometricCenter() {
        RecordingOperations operations = new RecordingOperations();

        CenteredPipPreviewTransform.applyPlayerPose(new Object(), 35.0F, operations);

        assertEquals(List.of("translate", "rotateY"), operations.names);
        assertArrayEquals(
                new float[]{0.0F, -0.5F, 0.0F},
                operations.values.get(0),
                EPSILON);
        assertArrayEquals(
                new float[]{(float) Math.toRadians(-35.0F)},
                operations.values.get(1),
                EPSILON);
    }

    @Test
    void capeHasNoSecondAttachmentTransformAndElytraHasOneVanillaOffset() {
        RecordingOperations cape = new RecordingOperations();
        RecordingOperations elytra = new RecordingOperations();

        CenteredPipPreviewTransform.applyAttachment(
                new Object(), PreviewRenderer.CapeMode.CAPE, cape);
        CenteredPipPreviewTransform.applyAttachment(
                new Object(), PreviewRenderer.CapeMode.ELYTRA, elytra);

        assertEquals(List.of(), cape.names);
        assertEquals(List.of("translate"), elytra.names);
        assertArrayEquals(new float[]{0.0F, 0.0F, 0.125F}, elytra.values.get(0), EPSILON);
    }

    @Test
    void pipScaleRangeKeepsTheSameScreenAndModelCenter() {
        for (float zoom : new float[]{0.68F, 1.0F, 2.0F}) {
            CenteredPlayerPreviewGeometry.Layout layout =
                    CenteredPlayerPreviewGeometry.fit(40, 20, 160, 220, zoom);
            assertEquals(120.0F, layout.centerX(), EPSILON);
            assertEquals(130.0F, layout.centerY(), EPSILON);
            assertEquals(-0.5F, CenteredPipPreviewTransform.PLAYER_CENTER_Y, EPSILON);
        }
    }

    @Test
    void neutralElytraUsesTheVanillaStandingWingAngles() {
        assertEquals(
                (float) Math.toRadians(15.0F),
                CenteredPipPreviewTransform.ELYTRA_ROT_X,
                EPSILON);
        assertEquals(0.0F, CenteredPipPreviewTransform.ELYTRA_ROT_Y, EPSILON);
        assertEquals(
                (float) Math.toRadians(-15.0F),
                CenteredPipPreviewTransform.ELYTRA_ROT_Z,
                EPSILON);
    }

    @Test
    void liveEntityAndSubmittedModelPitchUseSeparateDepthConventions() {
        assertEquals(
                (float) Math.toRadians(25.0F),
                CenteredPipPreviewTransform.pitchRadians(25.0F),
                EPSILON);
        assertEquals(
                (float) Math.toRadians(-25.0F),
                CenteredPipPreviewTransform.pitchRadians(-25.0F),
                EPSILON);
        assertEquals(
                (float) Math.toRadians(-25.0F),
                CenteredPipPreviewTransform.modelPitchRadians(25.0F),
                EPSILON);
        assertEquals(
                (float) Math.toRadians(25.0F),
                CenteredPipPreviewTransform.modelPitchRadians(-25.0F),
                EPSILON);
    }

    private static final class RecordingOperations
            implements CenteredPipPreviewTransform.Operations<Object> {
        private final List<String> names = new ArrayList<>();
        private final List<float[]> values = new ArrayList<>();

        @Override
        public void translate(Object context, float x, float y, float z) {
            names.add("translate");
            values.add(new float[]{x, y, z});
        }

        @Override
        public void rotateY(Object context, float radians) {
            names.add("rotateY");
            values.add(new float[]{radians});
        }
    }
}
