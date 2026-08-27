package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaPlayerModelTransformTest {
    private static final float EPSILON = 0.00001F;

    @Test
    void preservesVanillaInventoryAndLivingEntityOperationOrder() {
        RecordingOperations operations = new RecordingOperations();

        VanillaPlayerModelTransform.apply(new Object(), 3.0F, 45.0F, -30.0F, operations);

        assertEquals(List.of("scale", "rotateZThenX", "rotateY", "scale", "translate"),
                operations.names);
        assertArrayEquals(new float[]{3.0F, 3.0F, -3.0F}, operations.values.get(0), EPSILON);
        assertArrayEquals(
                new float[]{(float) Math.PI, (float) Math.toRadians(-30.0)},
                operations.values.get(1),
                EPSILON);
        assertArrayEquals(
                new float[]{(float) Math.toRadians(45.0)},
                operations.values.get(2),
                EPSILON);
        assertArrayEquals(new float[]{-1.0F, -1.0F, 1.0F}, operations.values.get(3), EPSILON);
        assertArrayEquals(new float[]{0.0F, -1.501F, 0.0F}, operations.values.get(4), EPSILON);
    }

    @Test
    void neutralPoseKeepsScreenAxesAndFlipsOnlyDepth() {
        MatrixOperations operations = new MatrixOperations();

        VanillaPlayerModelTransform.apply(operations.matrix, 2.0F, 0.0F, 0.0F, operations);

        assertArrayEquals(
                new float[]{
                        2.0F, 0.0F, 0.0F,
                        0.0F, 2.0F, 0.0F,
                        0.0F, 0.0F, -2.0F
                },
                operations.matrix,
                EPSILON);
    }

    @Test
    void centeredTransformMovesTheFullVisibleMeshMidpointToTheRotationOrigin() {
        RecordingOperations operations = new RecordingOperations();

        VanillaPlayerModelTransform.applyCentered(
                new Object(), 2.0F, -15.0F, 25.0F, operations);

        assertEquals(List.of(
                        "scale", "rotateZThenX", "rotateY", "scale", "translate"),
                operations.names);
        assertArrayEquals(
                new float[]{0.0F, -0.5F, 0.0F},
                operations.values.get(4),
                EPSILON);
    }

    @Test
    void pictureInPicturePoseOmitsOnlyTheScaleOwnedByTheHostRenderer() {
        RecordingOperations direct = new RecordingOperations();
        RecordingOperations pictureInPicture = new RecordingOperations();

        VanillaPlayerModelTransform.applyCentered(
                new Object(), 42.0F, 35.0F, -12.0F, direct);
        VanillaPlayerModelTransform.applyCenteredPose(
                new Object(), 35.0F, -12.0F, pictureInPicture);

        assertEquals(direct.names.subList(1, direct.names.size()), pictureInPicture.names);
        for (int index = 0; index < pictureInPicture.values.size(); index++) {
            assertArrayEquals(
                    direct.values.get(index + 1),
                    pictureInPicture.values.get(index),
                    EPSILON);
        }
    }

    private static final class RecordingOperations
            implements VanillaPlayerModelTransform.Operations<Object> {
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

    private static final class MatrixOperations
            implements VanillaPlayerModelTransform.Operations<float[]> {
        private final float[] matrix = {
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                0.0F, 0.0F, 1.0F
        };

        @Override
        public void scale(float[] context, float x, float y, float z) {
            multiply(context, new float[]{
                    x, 0.0F, 0.0F,
                    0.0F, y, 0.0F,
                    0.0F, 0.0F, z
            });
        }

        @Override
        public void rotateZThenX(float[] context, float zRadians, float xRadians) {
            float zCos = (float) Math.cos(zRadians);
            float zSin = (float) Math.sin(zRadians);
            multiply(context, new float[]{
                    zCos, -zSin, 0.0F,
                    zSin, zCos, 0.0F,
                    0.0F, 0.0F, 1.0F
            });
            float xCos = (float) Math.cos(xRadians);
            float xSin = (float) Math.sin(xRadians);
            multiply(context, new float[]{
                    1.0F, 0.0F, 0.0F,
                    0.0F, xCos, -xSin,
                    0.0F, xSin, xCos
            });
        }

        @Override
        public void rotateY(float[] context, float radians) {
            float cosine = (float) Math.cos(radians);
            float sine = (float) Math.sin(radians);
            multiply(context, new float[]{
                    cosine, 0.0F, sine,
                    0.0F, 1.0F, 0.0F,
                    -sine, 0.0F, cosine
            });
        }

        @Override
        public void translate(float[] ignored, float x, float y, float z) {

        }

        private static void multiply(float[] left, float[] right) {
            float[] product = new float[9];
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    float value = 0.0F;
                    for (int index = 0; index < 3; index++) {
                        value += left[row * 3 + index] * right[index * 3 + column];
                    }
                    product[row * 3 + column] = value;
                }
            }
            System.arraycopy(product, 0, left, 0, product.length);
        }
    }
}
