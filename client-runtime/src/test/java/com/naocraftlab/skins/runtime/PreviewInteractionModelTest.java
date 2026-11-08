package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PreviewInteractionModelTest {
    private static final Bounds PREVIEW = new Bounds(10, 10, 100, 180);

    @Test
    void clippedGalleryPreviewCannotCapturePointerOutsideItsClip() {
        var s=TestFixtures.ready(TestFixtures.account(1),null,0);
        var view=new GalleryPresenter().present(s,320,180,0,0,com.naocraftlab.skins.client.PreviewRenderer.CapeMode.CAPE);
        var preview=view.previews().get(0);
        var clipped=new ViewSpec(view.screenId(),view.title(),view.width(),view.height(),view.panels(),view.texts(),view.widgets(),view.previews(),view.scrollbar(),view.tabGroups(),view.focusRequest(),
                java.util.List.of(new ViewSpec.ClipRegion("deny",new Bounds(200,100,20,20),java.util.List.of("gallery.preview"))),view.backEquipmentPreviews(),view.iconDecorations(),view.scrollSurfaces(),view.tooltipRegions(),view.progressDecorations(),view.navigationNodes());
        org.junit.jupiter.api.Assertions.assertFalse(PointerRouting.hit(clipped,preview.anchorBounds().x()+25,preview.anchorBounds().y()+25).anyPreview());
    }

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
    void capeModeAndOuterLayerFollowCanonicalRules() {
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
