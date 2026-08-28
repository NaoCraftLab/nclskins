package com.naocraftlab.skins.runtime;


public final class CatalogCardStyle {
    public static final int HIGHLIGHT_BACKGROUND_COLOR =
            CollectionHeaderStyle.HIGHLIGHT_BACKGROUND_COLOR;
    public static final int SELECTED_BACKGROUND_COLOR = 0x665A8FCB;
    public static final int FOCUS_FRAME_COLOR = 0xFFFFFFFF;
    public static final int FOCUS_FRAME_SHADOW_COLOR = 0xFF000000;
    public static final int FOCUS_FRAME_INSET = ViewNavigationPolicy.FOCUS_FRAME_INSET;
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

    public static boolean backgroundBehindContent(ViewSpec.WidgetKind kind) {
        return kind == ViewSpec.WidgetKind.CATALOG_CARD
                || kind == ViewSpec.WidgetKind.SELECTABLE_CARD
                || kind == ViewSpec.WidgetKind.CAPE_CARD;
    }

    public static int backgroundBehindContentColor(
            ViewSpec.Widget widget, boolean hoveredOrFocused) {
        if (!backgroundBehindContent(widget.kind())) {
            return TRANSPARENT_BACKGROUND_COLOR;
        }
        return selectionSelected(widget)
                ? SELECTED_BACKGROUND_COLOR
                : backgroundColor(widget.enabled(), hoveredOrFocused);
    }

    public static boolean selectionSelected(ViewSpec.Widget widget) {
        return selectionBackgroundBehindContent(widget.kind())
                && widget.value().filter("selected"::equals).isPresent();
    }

    public static boolean focusFrameSupported(ViewSpec.WidgetKind kind) {
        return kind == ViewSpec.WidgetKind.CATALOG_CARD
                || kind == ViewSpec.WidgetKind.SELECTABLE_CARD
                || kind == ViewSpec.WidgetKind.CAPE_CARD
                || kind == ViewSpec.WidgetKind.COMPATIBILITY_INDICATOR;
    }
}
