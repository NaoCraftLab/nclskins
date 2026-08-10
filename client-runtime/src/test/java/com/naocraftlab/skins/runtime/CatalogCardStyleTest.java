package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CatalogCardStyleTest {
    @Test
    void idleAndDisabledCardsRemainTransparent() {
        assertEquals(
                CatalogCardStyle.TRANSPARENT_BACKGROUND_COLOR,
                CatalogCardStyle.backgroundColor(true, false));
        assertEquals(
                CatalogCardStyle.TRANSPARENT_BACKGROUND_COLOR,
                CatalogCardStyle.backgroundColor(false, true));
    }

    @Test
    void activeHoverOrKeyboardFocusUsesTheSharedSubtleWhiteWash() {
        assertEquals(
                CollectionHeaderStyle.HIGHLIGHT_BACKGROUND_COLOR,
                CatalogCardStyle.backgroundColor(true, true));
        assertEquals(0x18FFFFFF, CatalogCardStyle.HIGHLIGHT_BACKGROUND_COLOR);
    }

    @Test
    void selectedImportCardPaintsBlueBehindContentAndNothingAboveIt() {
        assertEquals(
                CatalogCardStyle.SELECTED_BACKGROUND_COLOR,
                CatalogCardStyle.selectableBackgroundColor(true));
        assertEquals(0x665A8FCB, CatalogCardStyle.SELECTED_BACKGROUND_COLOR);
        assertEquals(
                CatalogCardStyle.TRANSPARENT_BACKGROUND_COLOR,
                CatalogCardStyle.selectableForegroundColor(true, true, true));
        assertEquals(
                CatalogCardStyle.HIGHLIGHT_BACKGROUND_COLOR,
                CatalogCardStyle.selectableForegroundColor(false, true, true));
    }
}
