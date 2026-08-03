package com.naocraftlab.skins.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewPreferencesTest {
    @AfterEach
    void restoreDefault() {
        PreviewPreferences.setCapeMode(PreviewRenderer.CapeMode.CAPE);
    }

    @Test
    void sharesCapeModeAcrossConsumers() {
        PreviewPreferences.setCapeMode(PreviewRenderer.CapeMode.ELYTRA);

        assertEquals(PreviewRenderer.CapeMode.ELYTRA, PreviewPreferences.capeMode());
    }

    @Test
    void doesNotTreatMissingCapeAsAPreference() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PreviewPreferences.setCapeMode(PreviewRenderer.CapeMode.OFF));
    }
}
