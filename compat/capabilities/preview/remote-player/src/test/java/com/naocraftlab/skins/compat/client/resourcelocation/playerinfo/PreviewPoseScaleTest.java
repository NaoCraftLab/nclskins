package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewPoseScaleTest {
    @Test
    void uniformMagnitudesPreserveIncomingRotationAndEveryAxisSign() {
        for (float magnitude : new float[]{Float.MIN_VALUE, 1, 3, 200, Float.MAX_VALUE}) {
            for (int mask = 0; mask < 8; mask++) {
                PoseStack pose = incomingPose();
                Matrix3f incoming = new Matrix3f(pose.last().normal());
                float x = (mask & 1) == 0 ? magnitude : -magnitude;
                float y = (mask & 2) == 0 ? magnitude : -magnitude;
                float z = (mask & 4) == 0 ? magnitude : -magnitude;
                PoseStack vanilla = incomingPose();
                vanilla.scale(x, y, z);
                PreviewPoseScale.apply(pose, x, y, z);
                assertMatrix(vanilla.last().pose().get(new float[16]),
                        pose.last().pose().get(new float[16]));
                for (Vector3f axis : new Vector3f[]{
                        new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)}) {
                    Vector3f expected = incoming.transform(new Vector3f(axis).mul(
                            Math.signum(x), Math.signum(y), Math.signum(z)));
                    Vector3f actual = pose.last().normal().transform(new Vector3f(axis));
                    assertEquals(1, actual.length(), 0.0001F);
                    assertEquals(expected.x, actual.x, 0.0001F);
                    assertEquals(expected.y, actual.y, 0.0001F);
                    assertEquals(expected.z, actual.z, 0.0001F);
                }
            }
        }
    }

    @Test
    void pushPopRestoresBothMatricesAndKeepsExistingNonuniformNormalTransform() {
        PoseStack pose = incomingPose();
        pose.scale(2, 3, 4);
        Matrix4f position = new Matrix4f(pose.last().pose());
        Matrix3f normal = new Matrix3f(pose.last().normal());
        pose.pushPose();
        PreviewPoseScale.apply(pose, 50, 50, -50);
        assertMatrix(new Matrix3f(normal).scale(1, 1, -1).get(new float[9]),
                pose.last().normal().get(new float[9]));
        pose.popPose();
        assertTrue(pose.clear());
        assertMatrix(position.get(new float[16]), pose.last().pose().get(new float[16]));
        assertMatrix(normal.get(new float[9]), pose.last().normal().get(new float[9]));
    }

    @Test
    void otherScalesDelegateToVanillaWithoutChangingItsContract() {
        for (float[] scale : new float[][]{
                {2, 3, 4}, {2, 3, -4}, {-2, -3, -4},
                {0, 0, 0}, {-0.0F, 0, 0}, {1, 0, -1},
                {Float.NaN, 1, 1}, {1, Float.NaN, 1}, {1, 1, Float.NaN},
                {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}}) {
            PoseStack actual = incomingPose();
            PoseStack vanilla = incomingPose();
            PreviewPoseScale.apply(actual, scale[0], scale[1], scale[2]);
            vanilla.scale(scale[0], scale[1], scale[2]);
            assertMatrix(vanilla.last().pose().get(new float[16]),
                    actual.last().pose().get(new float[16]));
            assertMatrix(vanilla.last().normal().get(new float[9]),
                    actual.last().normal().get(new float[9]));
        }
    }

    private static PoseStack incomingPose() {
        PoseStack pose = new PoseStack();
        pose.translate(12, -4, 120);
        pose.mulPose(new Quaternionf().rotateY(0.7F).rotateX(-0.3F).rotateZ(0.2F));
        return pose;
    }

    private static void assertMatrix(float[] expected, float[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(Float.floatToIntBits(expected[i]), Float.floatToIntBits(actual[i]));
        }
    }
}
