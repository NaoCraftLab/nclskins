package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import org.junit.jupiter.api.Test;

final class PreviewInteractionModelTest {
    private static final Bounds PREVIEW = new Bounds(10, 10, 100, 180);

    @Test
    void canonicalDragClampsPitchAndScrollClampsZoom() {
        PreviewInteractionModel model = PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE);
        assertSame(model, model.drag(20, 20));

        PreviewInteractionModel rotated = model.beginRotate(PREVIEW, 50, 50).drag(10, -100);
        assertTrue(rotated.rotating());
        assertEquals(-14.0F, rotated.yawDegrees());
        assertEquals(30.0F, rotated.pitchDegrees());
        assertFalse(rotated.endRotate().rotating());

        PreviewInteractionModel zoomed = rotated;
        for (int index = 0; index < 100; index++) {
            zoomed = zoomed.scroll(PREVIEW, 50, 50, 1.0);
        }
        assertEquals(PreviewInteractionModel.MAX_SCALE, zoomed.scale());
    }

    @Test
    void capeModeAndOuterLayerFollow262Rules() {
        PreviewInteractionModel model = PreviewInteractionModel.gallery();
        assertSame(model, model.cycleCapeMode(false));
        assertEquals(PreviewRenderer.CapeMode.ELYTRA, model.cycleCapeMode(true).capeMode());
        assertEquals(OuterLayerVisibility.noneVisible(), model.toggleOuterLayer().outerLayerVisibility());
    }

    @Test
    void individualAndGroupTogglesKeepACompleteSixPartMask() {
        PreviewInteractionModel model = PreviewInteractionModel.gallery()
                .toggleOuterLayerPart(OuterLayerPart.LEFT_ARM);
        assertFalse(model.outerLayerVisibility().visible(OuterLayerPart.LEFT_ARM));
        assertTrue(model.outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM));

        PreviewInteractionModel disabled = model.toggleOuterLayerGroup(
                java.util.List.of(OuterLayerPart.BODY, OuterLayerPart.RIGHT_ARM));
        assertFalse(disabled.outerLayerVisibility().visible(OuterLayerPart.BODY));
        assertFalse(disabled.outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM));
        assertTrue(disabled.toggleOuterLayerGroup(
                        java.util.List.of(OuterLayerPart.BODY, OuterLayerPart.RIGHT_ARM))
                .outerLayerVisibility()
                .allVisible(java.util.List.of(OuterLayerPart.BODY, OuterLayerPart.RIGHT_ARM)));
    }
}
