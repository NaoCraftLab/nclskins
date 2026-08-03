package com.naocraftlab.skins.client;

import java.util.Objects;


public final class VanillaPlayerModelTransform {
    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0);
    private static final float MODEL_ORIGIN_TO_FEET = -1.501F;

    private VanillaPlayerModelTransform() {
    }

    public static <C> void apply(
            C context,
            float scale,
            float yawDegrees,
            float pitchDegrees,
            Operations<C> operations) {
        validate(context, scale, yawDegrees, pitchDegrees, operations);
        operations.scale(context, scale, scale, -scale);
        applyModelTransform(context, yawDegrees, pitchDegrees, operations);
    }

    public static <C> void applyCentered(
            C context,
            float scale,
            float yawDegrees,
            float pitchDegrees,
            Operations<C> operations) {
        validate(context, scale, yawDegrees, pitchDegrees, operations);
        operations.scale(context, scale, scale, -scale);
        operations.translate(
                context,
                0.0F,
                CenteredPlayerPreviewGeometry.modernEntityTranslationY(
                        CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT),
                0.0F);
        applyModelTransform(context, yawDegrees, pitchDegrees, operations);
    }

    private static <C> void validate(
            C context,
            float scale,
            float yawDegrees,
            float pitchDegrees,
            Operations<C> operations) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operations, "operations");
        if (!Float.isFinite(scale)
                || scale <= 0.0F
                || !Float.isFinite(yawDegrees)
                || !Float.isFinite(pitchDegrees)) {
            throw new IllegalArgumentException("Player preview transform must be finite and positive");
        }
    }

    private static <C> void applyModelTransform(
            C context,
            float yawDegrees,
            float pitchDegrees,
            Operations<C> operations) {
        operations.rotateZThenX(
                context,
                (float) Math.PI,
                pitchDegrees * DEGREES_TO_RADIANS);


        operations.rotateY(context, yawDegrees * DEGREES_TO_RADIANS);

        operations.scale(context, -1.0F, -1.0F, 1.0F);
        operations.translate(context, 0.0F, MODEL_ORIGIN_TO_FEET, 0.0F);
    }


    public interface Operations<C> {
        void scale(C context, float x, float y, float z);

        void rotateZThenX(C context, float zRadians, float xRadians);

        void rotateY(C context, float radians);

        void translate(C context, float x, float y, float z);
    }
}
