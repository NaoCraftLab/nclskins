package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorPreviewTickGateTest {
    @Test
    void advancesOnlyOnceForEachNewActiveSettlingTick() {
        EditorPreviewTickGate gate = new EditorPreviewTickGate();

        assertFalse(gate.shouldTick(39.9F, false));
        assertTrue(gate.shouldTick(40.0F, true));
        assertFalse(gate.shouldTick(40.0F, true));
        assertFalse(gate.shouldTick(40.9F, true));
        assertTrue(gate.shouldTick(41.0F, true));
        assertFalse(gate.shouldTick(42.0F, false));
    }

    @Test
    void rejectsInvalidAge() {
        EditorPreviewTickGate gate = new EditorPreviewTickGate();
        assertThrows(IllegalArgumentException.class, () -> gate.shouldTick(Float.NaN, true));
        assertThrows(IllegalArgumentException.class, () -> gate.shouldTick(-1.0F, true));
    }
}
