package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CollectionHeaderStyleTest {
    @Test
    void geometryCentersTheLabelAndRuleAndReservesASeparatorPixel() {
        Bounds bounds = new Bounds(16, 65, 824, 16);
        CollectionHeaderStyle.Geometry geometry =
                CollectionHeaderStyle.geometry(bounds, 9, 72);

        assertEquals(805, CollectionHeaderStyle.maximumLabelWidth(bounds));
        assertEquals(
                new CollectionHeaderStyle.Geometry(22, 68, 72, 100, 834, 72),
                geometry);
        assertTrue(geometry.hasLine());

        assertEquals(
                new CollectionHeaderStyle.Geometry(22, 68, 805, 833, 834, 72),
                CollectionHeaderStyle.geometry(bounds, 9, 10_000));
        assertEquals(
                1,
                CollectionHeaderStyle.geometry(bounds, 9, 10_000).lineEndExclusive()
                        - CollectionHeaderStyle.geometry(bounds, 9, 10_000).lineStart());
        int labelToRuleGap = geometry.lineStart() - geometry.textX() - geometry.labelWidth();
        assertEquals(6, labelToRuleGap);
        assertEquals(labelToRuleGap, geometry.textX() - bounds.x());
        assertEquals(labelToRuleGap, bounds.right() - geometry.lineEndExclusive());
    }

    @Test
    void geometryReservesFourPixelsBeforeATrailingFourteenPixelInfoButton() {
        Bounds bounds = new Bounds(16, 65, 824, 16);
        int infoButtonWidth = 14;
        int infoButtonX = bounds.right() - 16;
        CollectionHeaderStyle.Geometry geometry =
                CollectionHeaderStyle.geometry(bounds, 9, 72, infoButtonWidth);

        assertEquals(791, CollectionHeaderStyle.maximumLabelWidth(bounds, infoButtonWidth));
        assertEquals(
                new CollectionHeaderStyle.Geometry(22, 68, 72, 100, 820, 72),
                geometry);
        assertEquals(4, infoButtonX - geometry.lineEndExclusive());
        assertEquals(
                new CollectionHeaderStyle.Geometry(22, 68, 791, 819, 820, 72),
                CollectionHeaderStyle.geometry(bounds, 9, 10_000, infoButtonWidth));
    }

    @Test
    void paletteUsesOneSubtleFramelessHighlightForHoverAndFocusAcrossHosts() {
        assertEquals(
                new CollectionHeaderStyle.Palette(
                        CollectionHeaderStyle.LABEL_COLOR,
                        CollectionHeaderStyle.LINE_COLOR,
                        CollectionHeaderStyle.TRANSPARENT_BACKGROUND_COLOR),
                CollectionHeaderStyle.palette(true, false));
        assertEquals(
                new CollectionHeaderStyle.Palette(
                        CollectionHeaderStyle.HIGHLIGHTED_LABEL_COLOR,
                        CollectionHeaderStyle.HIGHLIGHTED_LINE_COLOR,
                        0x18FFFFFF),
                CollectionHeaderStyle.palette(true, true));
        assertEquals(
                new CollectionHeaderStyle.Palette(
                        CollectionHeaderStyle.DISABLED_LABEL_COLOR,
                        CollectionHeaderStyle.DISABLED_LINE_COLOR,
                        CollectionHeaderStyle.TRANSPARENT_BACKGROUND_COLOR),
                CollectionHeaderStyle.palette(false, true));
    }

    @Test
    void geometryRejectsInvalidFontMetrics() {
        Bounds bounds = new Bounds(0, 0, 20, 16);
        assertThrows(
                IllegalArgumentException.class,
                () -> CollectionHeaderStyle.geometry(bounds, 0, 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> CollectionHeaderStyle.geometry(bounds, 9, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CollectionHeaderStyle.geometry(bounds, 9, 4, -1));
    }
}
