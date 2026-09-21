package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class VanillaBackEquipmentTransformTest {
    private static final float EPSILON = 0.00001F;
    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;
    private static final double PROJECTED_MIN_X = -0.7051820703;
    private static final double PROJECTED_MAX_X = 0.7051820703;
    private static final double PROJECTED_MIN_Y = -0.7463644842;
    private static final double PROJECTED_MAX_Y = 0.7931422647;
    private static final double PROJECTED_UNION_WIDTH = 1.411;
    private static final double PROJECTED_UNION_HEIGHT = 1.587;

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
    void cardScaleUsesTheConservativeCenteredUnion() {
        float expected = 0.88F * Math.min(48.0F / (float) PROJECTED_UNION_WIDTH,
                64.0F / (float) PROJECTED_UNION_HEIGHT);
        assertEquals(
                expected,
                VanillaBackEquipmentTransform.fitScale(48, 64),
                EPSILON);
        assertThrows(
                IllegalArgumentException.class,
                () -> VanillaBackEquipmentTransform.fitScale(0, 64));
        assertThrows(
                IllegalArgumentException.class,
                () -> VanillaBackEquipmentTransform.fitScale(-1, 64));
        assertThrows(
                IllegalArgumentException.class,
                () -> VanillaBackEquipmentTransform.fitScale(48, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> VanillaBackEquipmentTransform.fitScale(48, -1));
    }

    @Test
    void nativeNeutralCornersProduceTheConservativeCenteredUnion() {
        List<Point> points = new ArrayList<>();
        for (NativeShape shape : NativeShape.values()) {
            points.addAll(shape.projectedCorners());
        }
        ProjectedBounds bounds = ProjectedBounds.of(points);

        assertEquals(1.4103641406, bounds.width(), EPSILON);
        assertEquals(1.5862845295, bounds.centeredHeight(), EPSILON);
        assertEquals(-0.7051820703, bounds.minX(), EPSILON);
        assertEquals(0.7051820703, bounds.maxX(), EPSILON);
        assertEquals(-0.7463644842, bounds.minY(), EPSILON);
        assertEquals(0.7931422647, bounds.maxY(), EPSILON);
        assertTrue(bounds.width() < PROJECTED_UNION_WIDTH);
        assertTrue(bounds.centeredHeight() < PROJECTED_UNION_HEIGHT);
    }

    @Test
    void standaloneFitScaleContainsTransformedNativeCornersAcrossSlotMatrix() {
        int[] widths = {24, 58, 59, 86, 120};
        int[] heights = {24, 47, 84, 85, 107};

        for (int width : widths) {
            for (int height : heights) {
                float scale = VanillaBackEquipmentTransform.fitScale(width, height);
                for (NativeShape shape : NativeShape.values()) {
                    for (Point point : shape.projectedCorners()) {
                        assertTrue(
                                Math.abs(point.x() * scale) <= width / 2.0 + EPSILON,
                                "horizontal containment: shape=" + shape
                                        + ", width=" + width + ", height=" + height
                                        + ", point=" + point + ", scale=" + scale);
                        assertTrue(
                                Math.abs(point.y() * scale) <= height / 2.0 + EPSILON,
                                "vertical containment: shape=" + shape
                                        + ", width=" + width + ", height=" + height
                                        + ", point=" + point + ", scale=" + scale);
                    }
                }
            }
        }
    }

    @Test
    void standaloneFitScaleUsesOneCommonScaleForUniformLogicalResize() {
        int width = 24;
        int height = 47;
        float scale = VanillaBackEquipmentTransform.fitScale(width, height);

        for (int factor : new int[]{2, 3, 4}) {
            assertEquals(factor * scale,
                    VanillaBackEquipmentTransform.fitScale(width * factor, height * factor),
                    factor * EPSILON);
        }
    }

    private enum NativeShape {
        LEGACY_CAPE,
        MODERN_CAPE,
        SIMPLE_ELYTRA,
        INFLATED_PLAYER_ELYTRA;

        private List<Point> projectedCorners() {
            return switch (this) {
                case LEGACY_CAPE -> capeCorners(0.0, 0.0);
                case MODERN_CAPE -> capeCorners(2.0, Math.PI);
                case SIMPLE_ELYTRA, INFLATED_PLAYER_ELYTRA -> elytraCorners();
            };
        }

        private static List<Point> capeCorners(double partOffsetZ, double partYRotation) {
            List<Point> points = new ArrayList<>();
            for (double x : new double[]{-5.0, 5.0}) {
                for (double y : new double[]{0.0, 16.0}) {
                    for (double z : new double[]{-1.0, 0.0}) {
                        Point point = divideByModelScale(new Point(x, y, z));
                        point = rotateY(point, partYRotation);
                        point = translate(point, 0.0, 0.0, partOffsetZ / 16.0);
                        point = rotateY(point, Math.PI);
                        point = rotateX(point, 6.0 * DEGREES_TO_RADIANS);
                        point = translate(point, 0.0, 0.0, 0.125);
                        points.add(applyStandaloneBase(point));
                    }
                }
            }
            return points;
        }

        private static List<Point> elytraCorners() {
            List<Point> points = new ArrayList<>();
            for (int side : new int[]{1, -1}) {
                double minX = side == 1 ? -11.0 : -1.0;
                double maxX = side == 1 ? 1.0 : 11.0;
                for (double x : new double[]{minX, maxX}) {
                    for (double y : new double[]{-1.0, 21.0}) {
                        for (double z : new double[]{-1.0, 3.0}) {
                            Point point = divideByModelScale(new Point(x, y, z));
                            point = rotateX(point, 15.0 * DEGREES_TO_RADIANS);
                            point = rotateZ(point, -side * 15.0 * DEGREES_TO_RADIANS);
                            point = translate(point, side * 5.0 / 16.0, 0.0, 0.0);
                            point = translate(point, 0.0, 0.0, 0.125);
                            points.add(applyStandaloneBase(point));
                        }
                    }
                }
            }
            return points;
        }
    }

    private static Point divideByModelScale(Point point) {
        return new Point(point.x() / 16.0, point.y() / 16.0, point.z() / 16.0);
    }

    private static Point applyStandaloneBase(Point point) {
        point = translate(point, 0.0, -0.625, 0.0);
        point = scale(point, -1.0, -1.0, 1.0);
        point = rotateY(point, Math.PI);
        point = rotateZ(point, Math.PI);
        return scale(point, 1.0, 1.0, -1.0);
    }

    private static Point rotateX(Point point, double radians) {
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        return new Point(
                point.x(),
                cosine * point.y() - sine * point.z(),
                sine * point.y() + cosine * point.z());
    }

    private static Point rotateY(Point point, double radians) {
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        return new Point(
                cosine * point.x() + sine * point.z(),
                point.y(),
                -sine * point.x() + cosine * point.z());
    }

    private static Point rotateZ(Point point, double radians) {
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        return new Point(
                cosine * point.x() - sine * point.y(),
                sine * point.x() + cosine * point.y(),
                point.z());
    }

    private static Point translate(Point point, double x, double y, double z) {
        return new Point(point.x() + x, point.y() + y, point.z() + z);
    }

    private static Point scale(Point point, double x, double y, double z) {
        return new Point(point.x() * x, point.y() * y, point.z() * z);
    }

    private record Point(double x, double y, double z) {
    }

    private record ProjectedBounds(
            double minX,
            double maxX,
            double minY,
            double maxY) {
        private static ProjectedBounds of(List<Point> points) {
            return new ProjectedBounds(
                    points.stream().mapToDouble(Point::x).min().orElseThrow(),
                    points.stream().mapToDouble(Point::x).max().orElseThrow(),
                    points.stream().mapToDouble(Point::y).min().orElseThrow(),
                    points.stream().mapToDouble(Point::y).max().orElseThrow());
        }

        private double width() {
            return maxX - minX;
        }

        private double centeredHeight() {
            return 2.0 * Math.max(Math.abs(minY), Math.abs(maxY));
        }
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
