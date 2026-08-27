package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPreviewLightingTest {
    @Test
    void fixedUiRigProducesASmoothMonotonicPitchGradient() {
        PlayerPreviewLighting.Direction light = PlayerPreviewLighting.centeredFront().primary();
        float low = frontLightAtPitch(light, -30.0F);
        float middle = frontLightAtPitch(light, 0.0F);
        float high = frontLightAtPitch(light, 30.0F);

        assertTrue(low > 0.0F);
        assertTrue(low < middle);
        assertTrue(middle < high);
    }

    private static float dot(
            PlayerPreviewLighting.Direction left, PlayerPreviewLighting.Direction right) {
        return left.x() * right.x() + left.y() * right.y() + left.z() * right.z();
    }

    private static float frontLightAtPitch(
            PlayerPreviewLighting.Direction light, float pitchDegrees) {
        float radians = (float) Math.toRadians(pitchDegrees);
        return dot(light, new PlayerPreviewLighting.Direction(
                0.0F, -(float) Math.sin(radians), (float) Math.cos(radians)));
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
