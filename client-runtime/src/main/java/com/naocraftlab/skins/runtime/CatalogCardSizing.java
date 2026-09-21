package com.naocraftlab.skins.runtime;

final class CatalogCardSizing {
    static final int GAP = 6;
    static final int MINIMUM_WIDTH = 68;
    static final int MINIMUM_HEIGHT = 72;
    static final int MAXIMUM_HEIGHT = 132;
    static final int SIZING_RESERVE = 16;

    private CatalogCardSizing() {
    }

    static CollectionGridLayout.CardMetrics metrics(int available, int viewportHeight) {
        return CollectionGridLayout.fittingCardMetrics(
                available,
                viewportHeight,
                SIZING_RESERVE,
                GAP,
                MINIMUM_WIDTH,
                MINIMUM_HEIGHT,
                MAXIMUM_HEIGHT);
    }

    static CollectionGridLayout.CardMetrics reference(
            int screenWidth, int screenHeight, ViewChromeMetrics chromeMetrics) {
        int available = Math.max(1,
                screenWidth - CollectionGridLayout.CONTENT_LEFT_INSET
                        - CollectionGridLayout.CONTENT_RIGHT_INSET);
        return metrics(available, chromeMetrics.catalogContentHeight(screenHeight));
    }

    static CollectionGridLayout.CardMetrics pane(
            int available, int screenHeight, ViewChromeMetrics chromeMetrics) {
        return metrics(available, chromeMetrics.catalogContentHeight(screenHeight));
    }
}
