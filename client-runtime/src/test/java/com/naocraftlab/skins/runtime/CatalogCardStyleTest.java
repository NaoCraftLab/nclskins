package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void capeAndImportSelectionsShareTheBehindContentLayer() {
        assertTrue(CatalogCardStyle.selectionBackgroundBehindContent(
                ViewSpec.WidgetKind.CAPE_CARD));
        assertTrue(CatalogCardStyle.selectionBackgroundBehindContent(
                ViewSpec.WidgetKind.SELECTABLE_CARD));
        assertFalse(CatalogCardStyle.selectionBackgroundBehindContent(
                ViewSpec.WidgetKind.CATALOG_CARD));
    }

    @Test
    void selectedCapeCardActivatesTheSharedBackgroundPass() {
        ViewSpec.Widget cape = new ViewSpec.Widget(
                "cape",
                ViewSpec.WidgetKind.CAPE_CARD,
                new Bounds(0, 0, 80, 86),
                UiMessage.literal("Cape", UiMessage.Severity.INFO),
                Optional.of("selected"),
                Optional.empty(),
                false,
                true,
                0);

        assertTrue(CatalogCardStyle.selectionSelected(cape));
        assertTrue(CatalogCardStyle.selectionSelected(ViewSpec.Widget.selectableCard(
                "import",
                new Bounds(0, 0, 80, 86),
                UiMessage.literal("Import", UiMessage.Severity.INFO),
                true,
                false)));
    }
}
