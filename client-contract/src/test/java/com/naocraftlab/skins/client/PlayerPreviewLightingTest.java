package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPreviewLightingTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(floats = {-30.0F, 0.0F, 30.0F})
    void pitchedCameraRigKeepsThePlayerFrontPositivelyLit(float pitchDegrees) {
        PlayerPreviewLighting.Rig rig =
                PlayerPreviewLighting.centeredFrontForPitch(pitchDegrees);
        float radians = (float) Math.toRadians(pitchDegrees);
        PlayerPreviewLighting.Direction front = new PlayerPreviewLighting.Direction(
                0.0F, -(float) Math.sin(radians), (float) Math.cos(radians));

        assertTrue(dot(rig.primary(), front) > 0.0F);
        assertEquals(dot(PlayerPreviewLighting.centeredFront().primary(),
                        new PlayerPreviewLighting.Direction(0.0F, 0.0F, 1.0F)),
                dot(rig.primary(), front), 0.0001F);
    }

    private static float dot(
            PlayerPreviewLighting.Direction left, PlayerPreviewLighting.Direction right) {
        return left.x() * right.x() + left.y() * right.y() + left.z() * right.z();
    }
    private static final float EPSILON = 0.00001F;

    @Test
    void centeredFrontRigMatchesModernInventoryOrientation() {
        PlayerPreviewLighting.Rig rig = PlayerPreviewLighting.centeredFront();
        float primaryLength = (float) Math.sqrt(2.04F);
        float fillLength = (float) Math.sqrt(1.04F);

        assertEquals(1.0F, length(rig.primary()), EPSILON);
        assertEquals(1.0F, length(rig.fill()), EPSILON);
        assertEquals(0.2F / primaryLength, rig.primary().x(), EPSILON);
        assertEquals(-1.0F / primaryLength, rig.primary().y(), EPSILON);
        assertEquals(1.0F / primaryLength, rig.primary().z(), EPSILON);
        assertEquals(-0.2F / fillLength, rig.fill().x(), EPSILON);
        assertEquals(-1.0F / fillLength, rig.fill().y(), EPSILON);
        assertEquals(0.0F, rig.fill().z(), EPSILON);
        assertTrue(rig.primary().x() > 0.0F);
        assertTrue(rig.fill().x() < 0.0F);
        assertTrue(rig.primary().y() < 0.0F);
        assertTrue(rig.fill().y() < 0.0F);
    }

    private static float length(PlayerPreviewLighting.Direction direction) {
        return (float) Math.sqrt(
                direction.x() * direction.x()
                        + direction.y() * direction.y()
                        + direction.z() * direction.z());
    }
}
