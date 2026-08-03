package com.naocraftlab.skins.runtime;


public final class CatalogCardStyle {
    public static final int HIGHLIGHT_BACKGROUND_COLOR =
            CollectionHeaderStyle.HIGHLIGHT_BACKGROUND_COLOR;
    public static final int TRANSPARENT_BACKGROUND_COLOR =
            CollectionHeaderStyle.TRANSPARENT_BACKGROUND_COLOR;

    private CatalogCardStyle() {}


    public static int backgroundColor(boolean active, boolean hoveredOrFocused) {
        return active && hoveredOrFocused
                ? HIGHLIGHT_BACKGROUND_COLOR
                : TRANSPARENT_BACKGROUND_COLOR;
    }
}
