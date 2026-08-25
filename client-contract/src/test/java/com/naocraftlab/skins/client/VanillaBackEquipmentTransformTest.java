package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


final class VanillaBackEquipmentTransformTest {
    private static final float EPSILON = 0.00001F;

    @Test
    void standalonePreviewUsesRearViewAndCenteredEquipmentPivotInExactOrder() {
        RecordingOperations operations = new RecordingOperations();

        VanillaBackEquipmentTransform.applyStandalone(new Object(), 12.5F, operations);

        assertEquals(
                List.of("scale", "rotateZThenX", "rotateY", "scale", "translate"),
                operations.names);
        assertArrayEquals(
                new float[]{12.5F, 12.5F, -12.5F}, operations.values.get(0), EPSILON);
        assertArrayEquals(
                new float[]{(float) Math.PI, 0.0F}, operations.values.get(1), EPSILON);
        assertArrayEquals(
                new float[]{(float) Math.PI}, operations.values.get(2), EPSILON);
        assertArrayEquals(
                new float[]{-1.0F, -1.0F, 1.0F}, operations.values.get(3), EPSILON);
        assertArrayEquals(
                new float[]{0.0F, -0.625F, 0.0F}, operations.values.get(4), EPSILON);
    }

    @Test
    void capeAttachmentMatchesStationaryVanillaCapeLayerTransform() {
        RecordingOperations operations = new RecordingOperations();

        VanillaBackEquipmentTransform.applyCapeAttachment(new Object(), operations);

        assertEquals(List.of("translate", "rotateX", "rotateY"), operations.names);
        assertArrayEquals(
                new float[]{0.0F, 0.0F, 0.125F}, operations.values.get(0), EPSILON);
        assertArrayEquals(
                new float[]{(float) Math.toRadians(6.0)}, operations.values.get(1), EPSILON);
        assertArrayEquals(
                new float[]{(float) Math.PI}, operations.values.get(2), EPSILON);
    }

    @Test
    void standaloneRearViewCapeDoesNotApplyASecondHalfTurn() {
        RecordingOperations operations = new RecordingOperations();

        VanillaBackEquipmentTransform.applyStandaloneCapeAttachment(
                new Object(), operations);

        assertEquals(List.of("translate", "rotateX"), operations.names);
        assertArrayEquals(
                new float[]{0.0F, 0.0F, 0.125F}, operations.values.get(0), EPSILON);
        assertArrayEquals(
                new float[]{(float) Math.toRadians(6.0)}, operations.values.get(1), EPSILON);
    }

    @Test
    void standaloneCapeCardAddsTheUvCorrectingHalfTurnOnlyForThatCardPath() {
        RecordingOperations operations = new RecordingOperations();

        VanillaBackEquipmentTransform.applyUvCorrectedStandaloneCapeAttachment(
                new Object(), operations);

        assertEquals(List.of("translate", "rotateX", "rotateY"), operations.names);
        assertArrayEquals(new float[]{(float) Math.PI}, operations.values.get(2), EPSILON);
    }

    @Test
    void capeAttachmentCanClearInflatedWorldlessPlayerLayersWithoutChangingRotations() {
        RecordingOperations operations = new RecordingOperations();

        VanillaBackEquipmentTransform.applyCapeAttachment(
                new Object(), 0.15625F, operations);

        assertEquals(List.of("translate", "rotateX", "rotateY"), operations.names);
        assertArrayEquals(
                new float[]{0.0F, 0.0F, 0.15625F}, operations.values.get(0), EPSILON);
        assertArrayEquals(
                new float[]{(float) Math.toRadians(6.0)}, operations.values.get(1), EPSILON);
        assertArrayEquals(
                new float[]{(float) Math.PI}, operations.values.get(2), EPSILON);
        assertThrows(
                IllegalArgumentException.class,
                () -> VanillaBackEquipmentTransform.applyCapeAttachment(
                        new Object(), 0.0F, operations));
    }

    @Test
    void elytraAttachmentMatchesVanillaElytraLayerTranslation() {
        RecordingOperations operations = new RecordingOperations();

        VanillaBackEquipmentTransform.applyElytraAttachment(new Object(), operations);

        assertEquals(List.of("translate"), operations.names);
        assertArrayEquals(
                new float[]{0.0F, 0.0F, 0.125F}, operations.values.get(0), EPSILON);
    }

    @Test
    void cardScaleUsesTheSharedReferenceGeometry() {
        assertEquals(
                0.88F * Math.min(48.0F / 1.5F, 64.0F / 1.25F),
                VanillaBackEquipmentTransform.fitScale(48, 64),
                EPSILON);
        assertThrows(
                IllegalArgumentException.class,
                () -> VanillaBackEquipmentTransform.fitScale(0, 64));
    }

    private static final class RecordingOperations
            implements VanillaBackEquipmentTransform.Operations<Object> {
        private final List<String> names = new ArrayList<>();
        private final List<float[]> values = new ArrayList<>();

        @Override
        public void scale(Object ignored, float x, float y, float z) {
            record("scale", x, y, z);
        }

        @Override
        public void rotateZThenX(Object ignored, float zRadians, float xRadians) {
            record("rotateZThenX", zRadians, xRadians);
        }

        @Override
        public void rotateX(Object ignored, float radians) {
            record("rotateX", radians);
        }

        @Override
        public void rotateY(Object ignored, float radians) {
            record("rotateY", radians);
        }

        @Override
        public void translate(Object ignored, float x, float y, float z) {
            record("translate", x, y, z);
        }

        private void record(String name, float... operands) {
            names.add(name);
            values.add(operands);
        }
    }
}
