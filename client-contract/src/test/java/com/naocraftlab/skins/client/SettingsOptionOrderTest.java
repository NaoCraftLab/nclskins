package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class SettingsOptionOrderTest {
    @Test
    void canonicalOptionsPlaceMainHandAfterBobView() {
        Object narrator = new Object();
        Object bobView = new Object();
        Object contrast = new Object();
        Object mainHand = new Object();
        Object autoJump = new Object();

        List<Object> result = SettingsOptionOrder.insertAfterFirstPresent(
                List.of(narrator, bobView, contrast),
                mainHand,
                List.of(bobView, autoJump));

        assertEquals(4, result.size());
        assertSame(bobView, result.get(1));
        assertSame(mainHand, result.get(2));
        assertSame(contrast, result.get(3));
    }

    @Test
    void legacyOptionsUseAutoJumpFallbackAndNeverDuplicateMainHand() {
        Object narrator = new Object();
        Object bobView = new Object();
        Object autoJump = new Object();
        Object mainHand = new Object();

        List<Object> inserted = SettingsOptionOrder.insertAfterFirstPresent(
                List.of(narrator, autoJump),
                mainHand,
                List.of(bobView, autoJump));
        List<Object> repeated = SettingsOptionOrder.insertAfterFirstPresent(
                inserted,
                mainHand,
                List.of(bobView, autoJump));

        assertEquals(3, repeated.size());
        assertSame(autoJump, repeated.get(1));
        assertSame(mainHand, repeated.get(2));
    }

    @Test
    void missingAnchorsAppendWithoutMutatingTheVanillaList() {
        Object narrator = new Object();
        Object mainHand = new Object();
        List<Object> vanilla = List.of(narrator);

        List<Object> result = SettingsOptionOrder.insertAfterFirstPresent(
                vanilla, mainHand, List.of(new Object()));

        assertEquals(1, vanilla.size());
        assertEquals(2, result.size());
        assertSame(mainHand, result.get(1));
    }
}
