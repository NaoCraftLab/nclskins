package com.naocraftlab.skins.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;


final class CollectionGridLayout {
    static final int CONTENT_LEFT_INSET = 16;
    static final int CONTENT_RIGHT_INSET = 14;
    static final int SCROLLBAR_RIGHT_INSET = 9;
    static final int COLLECTION_HEADER_HEIGHT = 16;
    static final int COLLECTION_HEADER_GAP = 4;
    static final int COLLECTION_BOTTOM_PADDING = 8;

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
        int contentBottom = Math.max(
                contentTop + 1,
                height - footerHeight - contentBottomPadding);
        int contentRight = Math.max(CONTENT_LEFT_INSET + 1, width - CONTENT_RIGHT_INSET);
        int available = Math.max(1, contentRight - CONTENT_LEFT_INSET);
        int viewportHeight = Math.max(1, contentBottom - contentTop);
        CardMetrics metrics = cardMetrics(available, viewportHeight, collectionHeaderHeight,
                cardGap, minimumCardWidth, maximumCardWidth, minimumCardHeight, maximumCardHeight);
        return calculate(width, height, contentTop, footerHeight, contentTopPadding,
                contentBottomPadding, collectionHeaderHeight, cardGap, metrics, scrollOffset, sections);
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
            CardMetrics metrics,
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
        metrics = Objects.requireNonNull(metrics, "metrics");
        int contentBottom = Math.max(
                contentTop + 1,
                height - footerHeight - contentBottomPadding);
        int contentStart = Math.min(contentBottom, contentTop + contentTopPadding);
        int contentRight = Math.max(CONTENT_LEFT_INSET + 1, width - CONTENT_RIGHT_INSET);
        int cardStartX = CONTENT_LEFT_INSET;
        int viewportHeight = Math.max(1, contentBottom - contentTop);
        int columns = metrics.columns();
        int cardWidth = metrics.width();
        int cardHeight = metrics.height();
        int totalHeight = contentTopPadding;
        int itemCount = 0;
        for (Section section : sections) {
            Objects.requireNonNull(section, "sections contains null");
            itemCount += section.itemCount();
            totalHeight += collectionHeaderHeight + COLLECTION_HEADER_GAP;
            if (!section.collapsed()) {
                int rows = (section.itemCount() + columns - 1) / columns;
                totalHeight += rows * (section.cardHeight().orElse(cardHeight) + cardGap)
                        + COLLECTION_BOTTOM_PADDING;
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

    static CardMetrics cardMetrics(
            int available, int viewportHeight, int heightReserve, int gap,
            int minimumWidth, int maximumWidth, int minimumHeight, int maximumHeight) {
        int columns = Math.max(2, Math.min(9, (available + gap) / (minimumWidth + gap)));
        CardMetrics metrics = cardMetricsForColumns(available, viewportHeight, heightReserve, gap,
                minimumHeight, maximumHeight, columns);
        return new CardMetrics(columns, Math.min(maximumWidth, metrics.width()), metrics.height());
    }

    static CardMetrics fittingCardMetrics(
            int available, int viewportHeight, int heightReserve, int gap,
            int minimumWidth, int minimumHeight, int maximumHeight) {
        int columns = Math.max(2, (available + gap) / (minimumWidth + gap));
        return cardMetricsForColumns(available, viewportHeight, heightReserve, gap,
                minimumHeight, maximumHeight, columns);
    }

    private static CardMetrics cardMetricsForColumns(
            int available, int viewportHeight, int heightReserve, int gap,
            int minimumHeight, int maximumHeight, int columns) {
        int width = Math.max(1, (available - (columns - 1) * gap) / columns);
        int height = Math.min(maximumHeight, Math.max(minimumHeight, viewportHeight - heightReserve - 12));
        return new CardMetrics(columns, width, height);
    }

    record CardMetrics(int columns, int width, int height) {}

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
                new Bounds(Math.max(0, width - SCROLLBAR_RIGHT_INSET), contentTop, 6, trackHeight),
                new Bounds(Math.max(0, width - SCROLLBAR_RIGHT_INSET), thumbTop, 6, Math.max(1, thumbHeight)),
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
