package com.naocraftlab.skins.client;

import java.util.Objects;

public final class CenteredPipPreviewTransform {
    public static final float PLAYER_CENTER_Y = -0.5F;
    public static final float EQUIPMENT_CENTER_Y = -0.625F;
    public static final float ELYTRA_ATTACHMENT_Z = 0.125F;
    public static final float ELYTRA_ROT_X = (float) Math.PI / 12.0F;
    public static final float ELYTRA_ROT_Y = 0.0F;
    public static final float ELYTRA_ROT_Z = -(float) Math.PI / 12.0F;

    private CenteredPipPreviewTransform() {
    }

    public static float pitchRadians(float pitchDegrees) {
        requireFinite(pitchDegrees, "pitchDegrees");
        return (float) Math.toRadians(pitchDegrees);
    }

    public static float modelPitchRadians(float pitchDegrees) {
        return -pitchRadians(pitchDegrees);
    }

    public static <C> void applyPlayerPose(
            C context, float yawDegrees, Operations<C> operations) {
        applyCenteredYaw(context, PLAYER_CENTER_Y, yawDegrees, operations);
    }

    public static <C> void applyStandaloneEquipmentPose(
            C context, float yawDegrees, Operations<C> operations) {
        applyCenteredYaw(context, EQUIPMENT_CENTER_Y, yawDegrees, operations);
    }

    public static <C> void applyAttachment(
            C context, PreviewRenderer.CapeMode mode, Operations<C> operations) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(operations, "operations");
        if (mode == PreviewRenderer.CapeMode.ELYTRA) {
            operations.translate(context, 0.0F, 0.0F, ELYTRA_ATTACHMENT_Z);
        }
    }

    private static <C> void applyCenteredYaw(
            C context, float centerY, float yawDegrees, Operations<C> operations) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operations, "operations");
        requireFinite(yawDegrees, "yawDegrees");
        operations.translate(context, 0.0F, centerY, 0.0F);
        operations.rotateY(context, (float) Math.toRadians(-yawDegrees));
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    public interface Operations<C> {
        void translate(C context, float x, float y, float z);

        void rotateY(C context, float radians);
    }
}
