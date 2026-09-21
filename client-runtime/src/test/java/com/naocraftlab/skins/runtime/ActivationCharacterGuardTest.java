package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivationCharacterGuardTest {
    private final ActivationCharacterGuard guard = new ActivationCharacterGuard();

    @Test
    void activationAndItsRepeatsCannotReplaceNewlySelectedText() {
        guard.keyPressed(true);
        guard.activated(true);
        assertTrue(guard.charTyped(' '));
        guard.keyPressed(true);
        guard.activated(false);
        assertTrue(guard.charTyped(' '));
        guard.keyReleased(true);
        guard.keyPressed(true);
        assertFalse(guard.charTyped(' '));
    }

    @Test
    void missingActivationCharacterDoesNotSwallowNextIndependentSpace() {
        guard.keyPressed(true);
        guard.activated(true);
        guard.keyReleased(true);
        guard.keyPressed(true);
        assertFalse(guard.charTyped(' '));
    }

    @Test
    void normalTextAndEnterNeverArmSuppression() {
        guard.keyPressed(true);
        assertFalse(guard.charTyped(' '));
        guard.keyPressed(false);
        guard.activated(false);
        assertFalse(guard.charTyped(' '));
        assertFalse(guard.charTyped('я'));
    }

    @Test
    void independentKeyTextAndPointerOrScreenResetClearOwnership() {
        guard.activated(true);
        guard.keyPressed(false);
        assertFalse(guard.charTyped(' '));
        guard.activated(true);
        assertFalse(guard.charTyped('я'));
        assertFalse(guard.charTyped(' '));
        guard.activated(true);
        guard.reset();
        assertFalse(guard.charTyped(' '));
    }

    @Test
    void unrelatedReleaseDoesNotEndActivationPress() {
        guard.activated(true);
        guard.keyReleased(false);
        assertTrue(guard.charTyped(' '));
    }
}
