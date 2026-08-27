package com.naocraftlab.skins.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;


final class CollectionGridLayout {
    private CollectionGridLayout() {
    }

    static Layout calculate(
            int width,
            int height,
            int contentTop,
            int footerHeight,
            int contentTopPadding,
            int contentBottomPadding,
            int collectionHeaderHeight,
            int cardGap,
            int minimumCardWidth,
            int maximumCardWidth,
            int minimumCardHeight,
            int maximumCardHeight,
            int scrollOffset,
            List<Section> sections) {
        if (width <= 0
                || height <= 0
                || contentTop < 0
                || footerHeight < 0
                || contentTopPadding < 0
                || contentBottomPadding < 0) {
            throw new IllegalArgumentException("collection grid dimensions are invalid");
        }
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        int contentBottom = Math.max(
                contentTop + 1,
                height - footerHeight - contentBottomPadding);
        int contentStart = Math.min(contentBottom, contentTop + contentTopPadding);
        int contentRight = Math.max(17, width - 14);
        int available = Math.max(1, contentRight - 16);
        int columns = Math.max(2, Math.min(9,
                (available + cardGap) / (minimumCardWidth + cardGap)));
        int cardWidth = Math.min(
                maximumCardWidth,
                Math.max(1, (available - (columns - 1) * cardGap) / columns));
        int cardStartX = 16;
        int viewportHeight = Math.max(1, contentBottom - contentTop);
        int cardHeight = Math.min(
                maximumCardHeight,
                Math.max(minimumCardHeight,
                        viewportHeight - collectionHeaderHeight - 12));
        int totalHeight = contentTopPadding;
        int itemCount = 0;
        for (Section section : sections) {
            Objects.requireNonNull(section, "sections contains null");
            itemCount += section.itemCount();
            totalHeight += collectionHeaderHeight + 4;
            if (!section.collapsed()) {
                int rows = (section.itemCount() + columns - 1) / columns;
                totalHeight += rows * (cardHeight + cardGap) + 8;
            }
        }
        int maximum = Math.max(0, totalHeight - viewportHeight);
        int normalizedOffset = Math.max(0, Math.min(scrollOffset, maximum));
        Optional<ViewSpec.Scrollbar> scrollbar = maximum == 0
                ? Optional.empty()
                : Optional.of(verticalScrollbar(
                width,
                contentTop,
                contentBottom,
                totalHeight,
                viewportHeight,
                normalizedOffset,
                maximum));
        return new Layout(
                columns,
                cardWidth,
                cardStartX,
                cardHeight,
                contentRight,
                contentStart,
                contentBottom,
                maximum,
                itemCount,
                normalizedOffset,
                scrollbar);
    }

    private static ViewSpec.Scrollbar verticalScrollbar(
            int width,
            int contentTop,
            int contentBottom,
            int totalHeight,
            int viewportHeight,
            int offset,
            int maximum) {
        int trackHeight = Math.max(1, contentBottom - contentTop);
        int thumbHeight = Math.max(
                12,
                (int) Math.round(trackHeight * (viewportHeight / (double) totalHeight)));
        thumbHeight = Math.min(trackHeight, thumbHeight);
        int travel = Math.max(0, trackHeight - thumbHeight);
        int thumbTop = contentTop + (int) Math.round(travel * (offset / (double) maximum));
        return new ViewSpec.Scrollbar(
                new Bounds(Math.max(0, width - 9), contentTop, 6, trackHeight),
                new Bounds(Math.max(0, width - 9), thumbTop, 6, Math.max(1, thumbHeight)),
                offset,
                maximum,
                ViewSpec.Scrollbar.Orientation.VERTICAL);
    }

    record Section(int itemCount, boolean collapsed) {
        Section {
            if (itemCount < 0) {
                throw new IllegalArgumentException("collection item count must not be negative");
            }
        }
    }

    record Layout(
            int columns,
            int cardWidth,
            int cardStartX,
            int cardHeight,
            int contentRight,
            int contentStart,
            int contentBottom,
            int maximum,
            int itemCount,
            int scrollOffset,
            Optional<ViewSpec.Scrollbar> scrollbar) {
        Layout {
            scrollbar = Objects.requireNonNull(scrollbar, "scrollbar");
        }
    }
}
