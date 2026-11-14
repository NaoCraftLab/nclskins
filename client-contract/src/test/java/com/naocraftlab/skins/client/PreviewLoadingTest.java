package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewLoadingTest {
    @Test void frameUsesOneSemanticStripAndIntegerCenteredNearestCropAtBothSizes() {
        for (int phase = 0; phase < 8; phase++) {
            for (int size : new int[]{16, 32}) {
                var frame = PreviewLoading.frame(phase * 100_000_000L, 7, 13, 51, 67, size);
                assertEquals("nclskins:textures/gui/icons/status/preview_loading.png", frame.resource());
                assertEquals(7 + (51 - size) / 2, frame.left());
                assertEquals(13 + (67 - size) / 2, frame.top());
                assertEquals(size, frame.size());
                assertEquals(0, frame.u());
                assertEquals(phase * 32, frame.v());
                assertEquals(32, frame.width());
                assertEquals(32, frame.height());
                assertEquals(32, frame.textureWidth());
                assertEquals(256, frame.textureHeight());
            }
        }
    }

    @Test void sharedMonotonicPolicyHasExactDelayAndFpsIndependentWrap() {
        assertFalse(PreviewLoading.delayed(17, 299_000_016L));
        assertTrue(PreviewLoading.delayed(17, 300_000_017L));
        assertEquals(7, PreviewLoading.phase(799_999_999L));
        assertEquals(0, PreviewLoading.phase(800_000_000L));
        assertEquals(3, PreviewLoading.phase(1_900_000_000L));
        assertEquals(7, PreviewLoading.phase(-1));
    }
}
