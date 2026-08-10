package com.naocraftlab.skins.runtime;


public final class CatalogCardStyle {
    public static final int HIGHLIGHT_BACKGROUND_COLOR =
            CollectionHeaderStyle.HIGHLIGHT_BACKGROUND_COLOR;
    public static final int SELECTED_BACKGROUND_COLOR = 0x665A8FCB;
    public static final int TRANSPARENT_BACKGROUND_COLOR =
            CollectionHeaderStyle.TRANSPARENT_BACKGROUND_COLOR;

    private CatalogCardStyle() {}


    public static int backgroundColor(boolean active, boolean hoveredOrFocused) {
        return active && hoveredOrFocused
                ? HIGHLIGHT_BACKGROUND_COLOR
                : TRANSPARENT_BACKGROUND_COLOR;
    }

    public static int selectableBackgroundColor(boolean selected) {
        return selected ? SELECTED_BACKGROUND_COLOR : TRANSPARENT_BACKGROUND_COLOR;
    }

    public static int selectableForegroundColor(
            boolean selected, boolean active, boolean hoveredOrFocused) {
        return selected
                ? TRANSPARENT_BACKGROUND_COLOR
                : backgroundColor(active, hoveredOrFocused);
    }

    public static boolean selectionBackgroundBehindContent(ViewSpec.WidgetKind kind) {
        return kind == ViewSpec.WidgetKind.CAPE_CARD
                || kind == ViewSpec.WidgetKind.SELECTABLE_CARD;
    }

    public static boolean selectionSelected(ViewSpec.Widget widget) {
        return selectionBackgroundBehindContent(widget.kind())
                && widget.value().filter("selected"::equals).isPresent();
    }
}
