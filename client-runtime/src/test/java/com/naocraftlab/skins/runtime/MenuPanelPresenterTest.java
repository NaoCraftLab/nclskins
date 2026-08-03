package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MenuPanelPresenterTest {
    private final MenuPanelPresenter presenter = new MenuPanelPresenter();

    @Test
    void hidesWhenNoNonOverlappingSpaceOrHeight() {
        assertTrue(presenter.present(320, 149, 0, 0, new Bounds(60, 68, 200, 20)).isEmpty());
        assertTrue(presenter.present(300, 240, 0, 0, new Bounds(50, 60, 200, 20)).isEmpty());
    }

    @Test
    void alignsToActualAnchorAndCapsHeightAtFiveRows() {
        Bounds anchor = new Bounds(327, 142, 200, 20);

        MenuPanelPresenter.Layout layout =
                presenter.present(854, 480, 600, 200, anchor).orElseThrow();

        assertEquals(new Bounds(531, 142, 104, 116), layout.panelBounds());
        assertEquals(new Bounds(535, 145, 96, 110), layout.previewBounds());
        assertEquals(new Bounds(531, 142, 104, 116), layout.buttonBounds());
        assertEquals(9.35F, layout.yawDegrees(), 0.001F);
    }

    @Test
    void followsShiftedAndResizedNativeMenuRow() {
        Bounds anchor = new Bounds(240, 96, 230, 20);

        MenuPanelPresenter.Layout layout =
                presenter.present(720, 300, 0, 0, anchor).orElseThrow();

        assertEquals(new Bounds(474, 96, 104, 116), layout.panelBounds());
    }

    @Test
    void rejectsAnchorsOutsideTheScreen() {
        assertTrue(presenter
                .present(320, 240, 0, 0, new Bounds(200, 40, 140, 20))
                .isEmpty());
    }
}
