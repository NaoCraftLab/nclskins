package com.naocraftlab.skins.client.tck;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class BakedPlayerPoseAssertions {
    private BakedPlayerPoseAssertions() {
    }

    public static void neutral(float[] baseTransform, float[] overlayTransform,
                               boolean actualVisible, boolean expectedVisible) {
        assertArrayEquals(new float[3], java.util.Arrays.copyOfRange(baseTransform, 3, 6),
                0.00001F, "Neutral base rotation");
        assertArrayEquals(baseTransform, overlayTransform, 0.00001F,
                "Outer layer must follow the base part");
        assertEquals(expectedVisible, actualVisible, "Outer layer mask");
    }
}
