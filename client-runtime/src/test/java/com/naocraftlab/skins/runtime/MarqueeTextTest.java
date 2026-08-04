package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MarqueeTextTest {
    @Test
    void fittingTextNeverMoves() {
        assertEquals(0, MarqueeText.offset(40, 40, 0));
        assertEquals(0, MarqueeText.offset(20, 40, 30_000));
    }

    @Test
    void overflowStaysWithinTheHorizontalClipAtEveryPhase() {
        int overflow = 87 - 40;
        for (long elapsed = 0; elapsed <= 20_000; elapsed += 137) {
            int offset = MarqueeText.offset(87, 40, elapsed);
            assertTrue(offset >= 0);
            assertTrue(offset <= overflow);
        }
    }
}
