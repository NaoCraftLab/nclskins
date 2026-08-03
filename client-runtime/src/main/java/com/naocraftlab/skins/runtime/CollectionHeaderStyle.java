package com.naocraftlab.skins.runtime;


public final class CollectionHeaderStyle {
    public static final int LABEL_COLOR = 0xFFE8EDF6;
    public static final int HIGHLIGHTED_LABEL_COLOR = 0xFFFFFFFF;
    public static final int DISABLED_LABEL_COLOR = 0xFF6F7887;
    public static final int LINE_COLOR = 0x7F9BA8BC;
    public static final int HIGHLIGHTED_LINE_COLOR = 0xBFE8EDF6;
    public static final int DISABLED_LINE_COLOR = 0x4F6F7887;

    public static final int HIGHLIGHT_BACKGROUND_COLOR = 0x18FFFFFF;
    public static final int TRANSPARENT_BACKGROUND_COLOR = 0x00000000;

    private static final int LINE_GAP = 6;
    private static final int HORIZONTAL_PADDING = LINE_GAP;
    private static final int MINIMUM_LINE_WIDTH = 1;

    private CollectionHeaderStyle() {}


    public static int maximumLabelWidth(Bounds bounds) {
        return maximumLabelWidth(bounds, 0);
    }


    public static int maximumLabelWidth(Bounds bounds, int trailingControlWidth) {
        requireTrailingControlWidth(trailingControlWidth);
        return Math.max(
                0,
                bounds.width()
                        - HORIZONTAL_PADDING * 2
                        - trailingControlWidth
                        - LINE_GAP
                        - MINIMUM_LINE_WIDTH);
    }


    public static Palette palette(boolean active, boolean hoveredOrFocused) {
        if (!active) {
            return new Palette(
                    DISABLED_LABEL_COLOR,
                    DISABLED_LINE_COLOR,
                    TRANSPARENT_BACKGROUND_COLOR);
        }
        if (hoveredOrFocused) {
            return new Palette(
                    HIGHLIGHTED_LABEL_COLOR,
                    HIGHLIGHTED_LINE_COLOR,
                    HIGHLIGHT_BACKGROUND_COLOR);
        }
        return new Palette(LABEL_COLOR, LINE_COLOR, TRANSPARENT_BACKGROUND_COLOR);
    }


    public static Geometry geometry(Bounds bounds, int fontLineHeight, int renderedLabelWidth) {
        return geometry(bounds, fontLineHeight, renderedLabelWidth, 0);
    }


    public static Geometry geometry(
            Bounds bounds,
            int fontLineHeight,
            int renderedLabelWidth,
            int trailingControlWidth) {
        if (fontLineHeight <= 0) {
            throw new IllegalArgumentException("fontLineHeight must be positive");
        }
        if (renderedLabelWidth < 0) {
            throw new IllegalArgumentException("renderedLabelWidth must not be negative");
        }
        requireTrailingControlWidth(trailingControlWidth);

        int labelWidth = Math.min(
                renderedLabelWidth, maximumLabelWidth(bounds, trailingControlWidth));
        int textY = bounds.y() + Math.max(0, (bounds.height() - fontLineHeight) / 2);
        int textX = bounds.x() + HORIZONTAL_PADDING;
        int lineStart = textX + labelWidth + LINE_GAP;
        int lineEndExclusive = Math.max(
                lineStart, bounds.right() - HORIZONTAL_PADDING - trailingControlWidth);
        int lineY = textY + fontLineHeight / 2;
        return new Geometry(
                textX,
                textY,
                labelWidth,
                lineStart,
                lineEndExclusive,
                lineY);
    }

    private static void requireTrailingControlWidth(int trailingControlWidth) {
        if (trailingControlWidth < 0) {
            throw new IllegalArgumentException("trailingControlWidth must not be negative");
        }
    }

    public record Palette(int labelColor, int lineColor, int backgroundColor) {}

    public record Geometry(
            int textX,
            int textY,
            int labelWidth,
            int lineStart,
            int lineEndExclusive,
            int lineY) {
        public boolean hasLine() {
            return lineStart < lineEndExclusive;
        }
    }
}
