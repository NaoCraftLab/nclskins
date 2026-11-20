package com.naocraftlab.skins.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;


final class CollectionGridLayout {
    static final int COLLECTION_HEADER_HEIGHT = 16;
    static final int COLLECTION_HEADER_GAP = 4;
    static final int COMPACT_INSET = 8;

    private CollectionGridLayout() {
    }

    static Layout compact(int width, int height, int contentTop, int footerHeight, int topPadding,
                          CardMetrics metrics, int scrollOffset, List<Section> sections) {
        return calculate(width, height, contentTop, footerHeight, topPadding, 0, COLLECTION_HEADER_HEIGHT,
                CatalogCardSizing.COMPACT_GAP, metrics, scrollOffset, sections, COMPACT_INSET, COMPACT_INSET, 6, 2);
    }

    private static Layout calculate(int width, int height, int contentTop, int footerHeight,
                                    int contentTopPadding, int contentBottomPadding, int collectionHeaderHeight,
                                    int cardGap, CardMetrics metrics, int scrollOffset, List<Section> sections,
                                    int leftInset, int rightInset, int scrollbarInset, int sectionPadding) {
        if (width <= 0
                || height <= 0
                || contentTop < 0
                || footerHeight < 0
                || contentTopPadding < 0
                || contentBottomPadding < 0) {
            throw new IllegalArgumentException("collection grid dimensions are invalid");
        }
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        metrics = Objects.requireNonNull(metrics, "metrics");
        int contentBottom = Math.max(
                contentTop + 1,
                height - footerHeight - contentBottomPadding);
        int contentStart = Math.min(contentBottom, contentTop + contentTopPadding);
        int contentRight = Math.max(leftInset + 1, width - rightInset);
        int cardStartX = leftInset;
        int viewportHeight = Math.max(1, contentBottom - contentTop);
        int columns = metrics.columns();
        int cardWidth = metrics.width();
        int cardHeight = metrics.height();
        int totalHeight = contentTopPadding;
        int itemCount = 0;
        for (Section section : sections) {
            Objects.requireNonNull(section, "sections contains null");
            itemCount += section.itemCount();
            totalHeight += sectionHeight(section.itemCount(), section.collapsed(), columns,
                    section.cardHeight().orElse(cardHeight), collectionHeaderHeight, cardGap, sectionPadding);
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
                maximum, scrollbarInset));
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

    static int sectionHeight(int itemCount, boolean collapsed, int columns, int cardHeight, int headerHeight,
                             int cardGap, int bottomPadding) {
        int height = headerHeight + COLLECTION_HEADER_GAP;
        if (!collapsed) {
            height += ((itemCount + columns - 1) / columns) * (cardHeight + cardGap) + bottomPadding;
        }
        return height;
    }

    record CardMetrics(int columns, int width, int height) {}

    private static ViewSpec.Scrollbar verticalScrollbar(
            int width,
            int contentTop,
            int contentBottom,
            int totalHeight,
            int viewportHeight,
            int offset,
            int maximum, int rightInset) {
        int trackHeight = Math.max(1, contentBottom - contentTop);
        int thumbHeight = Math.max(
                12,
                (int) Math.round(trackHeight * (viewportHeight / (double) totalHeight)));
        thumbHeight = Math.min(trackHeight, thumbHeight);
        int travel = Math.max(0, trackHeight - thumbHeight);
        int thumbTop = contentTop + (int) Math.round(travel * (offset / (double) maximum));
        return new ViewSpec.Scrollbar(
                new Bounds(Math.max(0, width - rightInset), contentTop, 6, trackHeight),
                new Bounds(Math.max(0, width - rightInset), thumbTop, 6, Math.max(1, thumbHeight)),
                offset,
                maximum,
                ViewSpec.Scrollbar.Orientation.VERTICAL);
    }

    record Section(int itemCount, boolean collapsed, OptionalInt cardHeight) {
        Section(int itemCount, boolean collapsed) {
            this(itemCount, collapsed, OptionalInt.empty());
        }

        Section(int itemCount, boolean collapsed, int cardHeight) {
            this(itemCount, collapsed, OptionalInt.of(cardHeight));
        }

        Section {
            if (itemCount < 0) {
                throw new IllegalArgumentException("collection item count must not be negative");
            }
            cardHeight = Objects.requireNonNull(cardHeight, "cardHeight");
            if (cardHeight.isPresent() && cardHeight.getAsInt() <= 0) {
                throw new IllegalArgumentException("collection card height must be positive");
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
