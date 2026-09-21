package com.naocraftlab.skins.runtime;


public record ViewChromeMetrics(int catalogFooterHeight) {
    static final int CATALOG_TAB_BAR_HEIGHT = 24;
    static final int CATALOG_CONTROLS_GAP = 7;
    static final int CATALOG_CONTROLS_HEIGHT = 20;
    static final int CATALOG_CONTENT_TOP = CATALOG_TAB_BAR_HEIGHT
            + CATALOG_CONTROLS_GAP
            + CATALOG_CONTROLS_HEIGHT
            + CATALOG_CONTROLS_GAP;
    public static final ViewChromeMetrics STANDARD = new ViewChromeMetrics(33);

    public ViewChromeMetrics {
        if (catalogFooterHeight < 0) {
            throw new IllegalArgumentException("catalog footer height must not be negative");
        }
    }

    int catalogContentBottom(int screenHeight) {
        return Math.max(CATALOG_CONTENT_TOP + 1, screenHeight - catalogFooterHeight);
    }

    int catalogContentHeight(int screenHeight) {
        return Math.max(1, catalogContentBottom(screenHeight) - CATALOG_CONTENT_TOP);
    }
}
