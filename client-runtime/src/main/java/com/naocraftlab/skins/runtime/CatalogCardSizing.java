package com.naocraftlab.skins.runtime;

final class CatalogCardSizing {
    static final int COMPACT_GAP = 2;
    static final int COMPACT_HEIGHT = 96;
    static final int GAP = 6;

    private CatalogCardSizing() {
    }

    static CollectionGridLayout.CardMetrics reference(
            int screenWidth, int screenHeight, ViewChromeMetrics chromeMetrics) {
        int available = Math.max(1,
                screenWidth - CollectionGridLayout.COMPACT_INSET * 2);
        return compactSkin(available);
    }

    static CollectionGridLayout.CardMetrics compactSkin(int available) {
        int columns = Math.max(1, (available + COMPACT_GAP) / (48 + COMPACT_GAP));
        int width = Math.max(1, Math.min(64, (available - (columns - 1) * COMPACT_GAP) / columns));
        return new CollectionGridLayout.CardMetrics(columns, width, COMPACT_HEIGHT);
    }
}
