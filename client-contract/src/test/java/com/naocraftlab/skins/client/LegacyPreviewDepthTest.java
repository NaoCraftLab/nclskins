package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPreviewDepthTest {
    @Test
    void growsWithTheRenderedModelScale() {
        float normal = LegacyPreviewDepth.required(190.0F);
        float zoomed = LegacyPreviewDepth.required(380.0F);

        assertEquals(396.0F, normal);
        assertEquals(776.0F, zoomed);
        assertTrue(zoomed > normal);
    }

    @Test
    void accountsForTheDepthAlreadyAddedByVanilla() {
        assertEquals(346.0F, LegacyPreviewDepth.additional(190.0F, 50.0F));
        assertEquals(0.0F, LegacyPreviewDepth.additional(1.0F, 50.0F));
    }

    @Test
    void rejectsInvalidDepthInputs() {
        assertThrows(IllegalArgumentException.class, () -> LegacyPreviewDepth.required(0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyPreviewDepth.required(Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyPreviewDepth.additional(1.0F, -1.0F));
    }
}
