package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

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
}
