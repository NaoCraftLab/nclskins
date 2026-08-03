package com.naocraftlab.skins.runtime;


public final class InfoButtonStyle {
    public static final int LABEL_COLOR = 0xFFB8C0CC;
    public static final int HIGHLIGHTED_LABEL_COLOR = 0xFFFFFFFF;
    public static final int DISABLED_LABEL_COLOR = 0xFF777777;

    private InfoButtonStyle() {}

    public static int labelColor(boolean active, boolean hoveredOrFocused) {
        if (!active) {
            return DISABLED_LABEL_COLOR;
        }
        return hoveredOrFocused ? HIGHLIGHTED_LABEL_COLOR : LABEL_COLOR;
    }
}
