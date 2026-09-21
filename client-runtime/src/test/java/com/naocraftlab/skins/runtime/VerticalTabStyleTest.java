package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VerticalTabStyleTest {
    @Test
    void inactiveIconOffsetIsSharedByEditorAndProviders() {
        assertEquals(10, VerticalTabStyle.iconX(10, true));
        assertEquals(12, VerticalTabStyle.iconX(10, false));
    }

    @Test
    void tabSelectionIsIndependentOfCardSelection() {
        for (boolean selected : List.of(false, true)) {
            ViewSpec.Widget tab = ViewSpec.Widget.verticalTabButton(
                    "tab", new Bounds(10, 10, 24, 24), UiMessage.info("nclskins.editor.tab.cape"),
                    GuiIcon.EDITOR_TAB_CAPE, selected, true);
            assertEquals(selected, VerticalTabStyle.isSelected(tab));
            assertEquals(false, CatalogCardStyle.selectionSelected(tab));
        }
    }

    @Test
    void dividerJoinsBothFrameEdgesWithoutCrossingTheActiveOpening() {
        Bounds panel = new Bounds(428, 33, 426, 215);
        for (int index = 0; index < 2; index++) {
            Bounds tab = VerticalTabStyle.bounds(panel.x(), index);
            Bounds opening = VerticalTabStyle.selectedUnderlay(tab);
            List<Bounds> segments = VerticalTabStyle.separatorSegments(panel, tab);
            assertEquals(opening.y(), segments.get(0).bottom());
            assertEquals(opening.bottom(), segments.get(1).y());
            for (int y = tab.y(); y < tab.bottom(); y++) {
                int row = y;
                long coverage = segments.stream()
                        .filter(segment -> row >= segment.y() && row < segment.bottom()).count();
                assertEquals(row < opening.y() || row >= opening.bottom() ? 1L : 0L, coverage);
            }
        }
    }

    @Test
    void highlightedBorderCoversTheInactiveIconRightEdge() {
        Bounds tab = VerticalTabStyle.bounds(428, 0);
        int iconRightPixel = VerticalTabStyle.iconX(tab.x(), false) + 4 + 15;
        assertTrue(VerticalTabStyle.highlightOutline(tab).stream().anyMatch(edge -> edge.contains(iconRightPixel, tab.y() + 12)));
        assertTrue(VerticalTabStyle.highlightOutline(tab).stream().noneMatch(edge -> edge.contains(tab.right() - 1, tab.y() + 12)));
    }

    @Test
    void tabsOverlapTheDividerAndSelectedTabLeavesItsSeparatorGap() {
        assertEquals(new Bounds(406, 45, 24, 24), VerticalTabStyle.bounds(428, 0));
        assertEquals(new Bounds(406, 69, 24, 24), VerticalTabStyle.bounds(428, 1));
        assertEquals(List.of(
                new Bounds(428, 33, 2, 14),
                new Bounds(428, 67, 2, 181)),
                VerticalTabStyle.separatorSegments(
                        new Bounds(428, 33, 426, 215), new Bounds(406, 45, 24, 24)));
        assertEquals(new Bounds(408, 47, 22, 20), VerticalTabStyle.selectedUnderlay(
                new Bounds(406, 45, 24, 24)));
        assertEquals(new Bounds(428, 45, 2, 24), VerticalTabStyle.rightEdge(
                new Bounds(406, 45, 24, 24)));
    }
}
