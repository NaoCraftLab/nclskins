package com.naocraftlab.skins.core.png;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapeTimelineTest {
    @Test void pngStripUsesOneHundredMillisecondFramesAndSkipsStalledFrames() {
        CapeTimeline timeline = CapeTimeline.pngStrip(3);
        assertEquals(0, timeline.frameAt(0));
        assertEquals(0, timeline.frameAt(99));
        assertEquals(1, timeline.frameAt(100));
        assertEquals(2, timeline.frameAt(299));
        assertEquals(2, timeline.frameAt(2_999));
        assertEquals(0, timeline.frameAt(3_000));
    }

    @Test void gifPreservesNativeDelaysAndFiniteLoops() {
        CapeTimeline infinite = CapeTimeline.gif(new int[]{3, 20}, 0);
        assertArrayEquals(new int[]{30, 200}, infinite.delaysMillis());
        assertEquals(1, infinite.frameAt(30));
        assertEquals(0, infinite.frameAt(230));
        CapeTimeline finite = CapeTimeline.gif(new int[]{3, 20}, 1);
        assertEquals(2, finite.plays());
        assertEquals(0, finite.frameAt(230));
        assertEquals(1, finite.frameAt(460));
        assertEquals(1, finite.frameAt(Long.MAX_VALUE));
    }

    @Test void absentDelayIsSafeAndSingleFrameIsStatic() {
        assertArrayEquals(new int[]{100, 100}, CapeTimeline.gif(new int[]{0, 0}, 0).delaysMillis());
        assertEquals(0, CapeTimeline.gif(new int[]{3}, 1).frameAt(Long.MAX_VALUE));
        assertEquals(1, CapeTimeline.gif(new int[]{0, 1}, 0).frameAt(100));
    }

    @Test void invalidAndOverflowingTimelinesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CapeTimeline(new int[0], 0));
        assertThrows(IllegalArgumentException.class, () -> new CapeTimeline(new int[]{0}, 0));
        assertThrows(ArithmeticException.class, () -> new CapeTimeline(new int[]{Integer.MAX_VALUE}, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> CapeTimeline.gif(new int[]{-1}, 0));
    }
}
