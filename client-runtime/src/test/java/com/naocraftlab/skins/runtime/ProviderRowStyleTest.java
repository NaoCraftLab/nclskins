package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProviderRowStyleTest {
    @Test void keyboardFocusShowsFrameAndControlsWithoutSelectingPreview() {
        assertEquals(0xFFFFFFFF, ProviderRowStyle.frameColor(false, true, true));
        assertTrue(ProviderRowStyle.showControls(false, true, true));
        assertEquals(0, ProviderRowStyle.frameColor(false, true, false));
        assertFalse(ProviderRowStyle.showControls(false, true, false));
        assertTrue(ProviderRowStyle.showControls(true, false, false));
        assertEquals(0xFF808080, ProviderRowStyle.frameColor(true, false, false));
    }
}
