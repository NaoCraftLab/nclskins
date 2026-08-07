package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EditorPreviewClockTest {
    @Test
    void advancesAtTwentyTicksPerSecondWithoutWorldTicks() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        EditorPreviewClock clock = new EditorPreviewClock(nanos::get);

        assertEquals(40.0F, clock.ageTicks(40.0F));
        nanos.addAndGet(25_000_000L);
        assertEquals(40.5F, clock.ageTicks(999.0F));
        nanos.addAndGet(75_000_000L);
        assertEquals(42.0F, clock.ageTicks(-1.0F));
    }

    @Test
    void neverRunsBackwardWhenTheSourceClockMovesBackward() {
        AtomicLong nanos = new AtomicLong(200L);
        EditorPreviewClock clock = new EditorPreviewClock(nanos::get);

        assertEquals(7.0F, clock.ageTicks(7.0F));
        nanos.set(100L);
        assertEquals(7.0F, clock.ageTicks(0.0F));
    }

    @Test
    void aNewScreenClockStartsFromItsOwnPlayerAge() {
        AtomicLong nanos = new AtomicLong(0L);
        EditorPreviewClock first = new EditorPreviewClock(nanos::get);
        EditorPreviewClock reopened = new EditorPreviewClock(nanos::get);

        assertEquals(10.0F, first.ageTicks(10.0F));
        nanos.addAndGet(50_000_000L);
        assertEquals(11.0F, first.ageTicks(10.0F));
        assertEquals(80.0F, reopened.ageTicks(80.0F));
    }
}
