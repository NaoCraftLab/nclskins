package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.config.MenuPreviewPlacement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MenuPanelPresenterTest {
    private final MenuPanelPresenter presenter = new MenuPanelPresenter();

    @Test
    void leftMovesAllBoundsWithoutMirroringTheModel() {
        Bounds anchor = new Bounds(327, 142, 200, 20);
        var right = presenter.present(854, 480, 600, 200, anchor, MenuPreviewPlacement.RIGHT).orElseThrow();
        var left = presenter.present(854, 480, 288, 200, anchor, MenuPreviewPlacement.LEFT).orElseThrow();
        assertEquals(new Bounds(219, 142, 104, 116), left.panelBounds());
        assertEquals(new Bounds(223, 145, 96, 110), left.previewBounds());
        assertEquals(left.panelBounds(), left.buttonBounds());
        assertEquals(right.yawDegrees(), left.yawDegrees());
        assertEquals(right.pitchDegrees(), left.pitchDegrees());
        assertEquals(right.scale(), left.scale());
        assertTrue(presenter.present(854, 480, 600, 200, anchor, MenuPreviewPlacement.OFF).isEmpty());
    }

    @Test
    void leftUsesItsOwnSpaceAndNeverFallsBackToRight() {
        Bounds anchor = new Bounds(56, 20, 200, 20);
        var layout = presenter.present(854, 138, 0, 0, anchor, MenuPreviewPlacement.LEFT).orElseThrow();
        assertEquals(new Bounds(8, 20, 44, 82), layout.panelBounds());
        assertTrue(presenter.present(854, 137, 0, 0, anchor, MenuPreviewPlacement.LEFT).isEmpty());
        assertTrue(presenter.present(854, 480, 0, 0, new Bounds(55, 20, 200, 20), MenuPreviewPlacement.LEFT).isEmpty());
        assertTrue(presenter.present(854, 480, 0, 0, new Bounds(55, 20, 200, 20), MenuPreviewPlacement.RIGHT).isPresent());
    }

    @Test
    void preservesExactAvailableSpaceThresholdsAndInsets() {
        Bounds anchor = new Bounds(50, 20, 200, 20);
        var layout = presenter.present(306, 138, 276, 51, anchor, MenuPreviewPlacement.RIGHT).orElseThrow();
        assertEquals(new Bounds(254, 20, 44, 82), layout.panelBounds());
        assertEquals(new Bounds(258, 23, 36, 76), layout.previewBounds());
        assertEquals(layout.panelBounds(), layout.buttonBounds());
        assertEquals(0.0F, layout.yawDegrees(), 0.001F);
        assertEquals(0.056F, layout.pitchDegrees(), 0.001F);
        assertEquals(0.92F, layout.scale());
        assertTrue(presenter.present(305, 138, 0, 0, anchor, MenuPreviewPlacement.RIGHT).isEmpty());
        assertTrue(presenter.present(306, 137, 0, 0, anchor, MenuPreviewPlacement.RIGHT).isEmpty());
    }

    @Test
    void clampsPointerRotationAtBothExtremes() {
        Bounds anchor = new Bounds(327, 142, 200, 20);
        var upperRight = presenter.present(854, 480, 10000, -10000, anchor, MenuPreviewPlacement.RIGHT).orElseThrow();
        var lowerLeft = presenter.present(854, 480, -10000, 10000, anchor, MenuPreviewPlacement.RIGHT).orElseThrow();
        assertEquals(38.0F, upperRight.yawDegrees());
        assertEquals(24.0F, upperRight.pitchDegrees());
        assertEquals(-38.0F, lowerLeft.yawDegrees());
        assertEquals(-24.0F, lowerLeft.pitchDegrees());
    }

    @Test
    void hidesWhenNoNonOverlappingSpaceOrHeight() {
        assertTrue(presenter.present(320, 149, 0, 0, new Bounds(60, 68, 200, 20), MenuPreviewPlacement.RIGHT).isEmpty());
        assertTrue(presenter.present(300, 240, 0, 0, new Bounds(50, 60, 200, 20), MenuPreviewPlacement.RIGHT).isEmpty());
    }

    @Test
    void alignsToActualAnchorAndCapsHeightAtFiveRows() {
        Bounds anchor = new Bounds(327, 142, 200, 20);

        MenuPanelPresenter.Layout layout =
                presenter.present(854, 480, 600, 200, anchor, MenuPreviewPlacement.RIGHT).orElseThrow();

        assertEquals(new Bounds(531, 142, 104, 116), layout.panelBounds());
        assertEquals(new Bounds(535, 145, 96, 110), layout.previewBounds());
        assertEquals(new Bounds(531, 142, 104, 116), layout.buttonBounds());
        assertEquals(9.35F, layout.yawDegrees(), 0.001F);
    }

    @Test
    void followsShiftedAndResizedNativeMenuRow() {
        Bounds anchor = new Bounds(240, 96, 230, 20);

        MenuPanelPresenter.Layout layout =
                presenter.present(720, 300, 0, 0, anchor, MenuPreviewPlacement.RIGHT).orElseThrow();

        assertEquals(new Bounds(474, 96, 104, 116), layout.panelBounds());
    }

    @Test
    void rejectsAnchorsOutsideTheScreen() {
        assertTrue(presenter
                .present(320, 240, 0, 0, new Bounds(200, 40, 140, 20), MenuPreviewPlacement.RIGHT)
                .isEmpty());
    }
}
