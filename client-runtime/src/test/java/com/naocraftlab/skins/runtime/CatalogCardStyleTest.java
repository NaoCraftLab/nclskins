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
    void everyCardFillIsResolvedInTheBackgroundPassBeforePreviewContent() {
        ViewSpec.Widget catalog = new ViewSpec.Widget(
                "catalog",
                ViewSpec.WidgetKind.CATALOG_CARD,
                new Bounds(0, 0, 80, 86),
                UiMessage.literal("Catalog", UiMessage.Severity.INFO),
                Optional.empty(),
                Optional.empty(),
                true,
                true,
                0);
        ViewSpec.Widget selectedImport = ViewSpec.Widget.selectableCard(
                "import",
                new Bounds(0, 0, 80, 86),
                UiMessage.literal("Import", UiMessage.Severity.INFO),
                true,
                false);

        assertTrue(CatalogCardStyle.backgroundBehindContent(catalog.kind()));
        assertEquals(
                CatalogCardStyle.HIGHLIGHT_BACKGROUND_COLOR,
                CatalogCardStyle.backgroundBehindContentColor(catalog, true));
        assertEquals(
                CatalogCardStyle.SELECTED_BACKGROUND_COLOR,
                CatalogCardStyle.backgroundBehindContentColor(selectedImport, true));
        assertFalse(CatalogCardStyle.backgroundBehindContent(ViewSpec.WidgetKind.BUTTON));
        assertFalse(CatalogCardStyle.backgroundBehindContent(
                ViewSpec.WidgetKind.COMPATIBILITY_INDICATOR));
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

    @Test
    void focusFrameHasSharedVanillaPaletteAndSupportsCardsAndCompatibilityMarker() {
        assertEquals(0xFFFFFFFF, CatalogCardStyle.FOCUS_FRAME_COLOR);
        assertEquals(0xFF000000, CatalogCardStyle.FOCUS_FRAME_SHADOW_COLOR);
        assertEquals(ViewNavigationPolicy.FOCUS_FRAME_INSET, CatalogCardStyle.FOCUS_FRAME_INSET);
        assertTrue(CatalogCardStyle.focusFrameSupported(ViewSpec.WidgetKind.CATALOG_CARD));
        assertTrue(CatalogCardStyle.focusFrameSupported(ViewSpec.WidgetKind.SELECTABLE_CARD));
        assertTrue(CatalogCardStyle.focusFrameSupported(ViewSpec.WidgetKind.CAPE_CARD));
        assertTrue(CatalogCardStyle.focusFrameSupported(
                ViewSpec.WidgetKind.COMPATIBILITY_INDICATOR));
        assertFalse(CatalogCardStyle.focusFrameSupported(ViewSpec.WidgetKind.BUTTON));
        assertFalse(CatalogCardStyle.focusFrameSupported(ViewSpec.WidgetKind.TEXT_FIELD));
    }
}
