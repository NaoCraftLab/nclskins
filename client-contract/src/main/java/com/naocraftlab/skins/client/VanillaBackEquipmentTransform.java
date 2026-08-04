package com.naocraftlab.skins.client;

import java.util.Objects;


public final class VanillaBackEquipmentTransform {
    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0);
    private static final float MODEL_WIDTH = 1.5F;
    private static final float MODEL_HEIGHT = 1.25F;
    private static final float MODEL_PIVOT_Y = -MODEL_HEIGHT / 2.0F;
    private static final float FIT_PADDING = 0.88F;

    private VanillaBackEquipmentTransform() {
    }

    public static float fitScale(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Back-equipment preview bounds must be positive");
        }
        return FIT_PADDING * Math.min(width / MODEL_WIDTH, height / MODEL_HEIGHT);
    }

    public static <C> void applyStandalone(
            C context,
            float scale,
            Operations<C> operations) {
        validate(context, scale, operations);
        operations.scale(context, scale, scale, -scale);
        operations.rotateZThenX(context, (float) Math.PI, 0.0F);
        operations.rotateY(context, (float) Math.PI);
        operations.scale(context, -1.0F, -1.0F, 1.0F);
        operations.translate(context, 0.0F, MODEL_PIVOT_Y, 0.0F);
    }

    public static <C> void applyCapeAttachment(C context, Operations<C> operations) {
        validate(context, operations);
        operations.translate(context, 0.0F, 0.0F, 0.125F);
        operations.rotateX(context, 6.0F * DEGREES_TO_RADIANS);
        operations.rotateY(context, (float) Math.PI);
    }

    public static <C> void applyElytraAttachment(C context, Operations<C> operations) {
        validate(context, operations);
        operations.translate(context, 0.0F, 0.0F, 0.125F);
    }

    private static <C> void validate(
            C context,
            float scale,
            Operations<C> operations) {
        validate(context, operations);
        if (!Float.isFinite(scale) || scale <= 0.0F) {
            throw new IllegalArgumentException(
                    "Back-equipment preview scale must be finite and positive");
        }
    }

    private static <C> void validate(C context, Operations<C> operations) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operations, "operations");
    }

    public interface Operations<C> {
        void scale(C context, float x, float y, float z);

        void rotateZThenX(C context, float zRadians, float xRadians);

        void rotateX(C context, float radians);

        void rotateY(C context, float radians);

        void translate(C context, float x, float y, float z);
    }
}
