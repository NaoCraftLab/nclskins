package com.naocraftlab.skins.runtime;

public final class ProviderRowStyle {
    private ProviderRowStyle() {}

    public static int frameColor(boolean selected, boolean focused, boolean keyboard) {
        return focused && (selected || keyboard) ? 0xFFFFFFFF : selected ? 0xFF808080 : 0;
    }

    public static boolean showControls(boolean hovered, boolean focused, boolean keyboard) {
        return hovered || (focused && keyboard);
    }
}
