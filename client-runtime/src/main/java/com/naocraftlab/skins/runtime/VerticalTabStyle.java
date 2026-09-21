package com.naocraftlab.skins.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class VerticalTabStyle {
    public static final int WIDTH = 24;
    public static final int HEIGHT = 24;
    public static final int DIVIDER_OVERLAP = 2;
    public static final int FIRST_TAB_TOP = 45;

    private VerticalTabStyle() {}

    public static Bounds bounds(int dividerX, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("tab index must not be negative");
        }
        return new Bounds(
                Math.max(0, dividerX - WIDTH + DIVIDER_OVERLAP),
                FIRST_TAB_TOP + index * HEIGHT,
                WIDTH,
                HEIGHT);
    }

    public static int iconX(int tabX, boolean selected) {
        return tabX + (selected ? 0 : 2);
    }

    public static List<Bounds> highlightOutline(Bounds tab) {
        return List.of(new Bounds(tab.x() + 5, tab.y() + 1, tab.width() - 7, 1),
                new Bounds(tab.x() + 5, tab.bottom() - 2, tab.width() - 7, 1),
                new Bounds(tab.x() + 5, tab.y() + 2, 1, tab.height() - 4),
                new Bounds(tab.right() - 3, tab.y() + 2, 1, tab.height() - 4));
    }

    public static Bounds underlay(Bounds tabBounds, boolean selected) {
        Objects.requireNonNull(tabBounds, "tabBounds");
        int horizontalInset = selected ? 2 : 5;
        int dividerInset = selected ? 0 : 2;
        if (tabBounds.width() <= horizontalInset + dividerInset || tabBounds.height() <= 4) {
            throw new IllegalArgumentException("tab bounds are too small for an underlay");
        }
        return new Bounds(
                tabBounds.x() + horizontalInset,
                tabBounds.y() + 2,
                tabBounds.width() - horizontalInset - dividerInset,
                tabBounds.height() - 4);
    }


    public static boolean highlighted(boolean hovered, boolean focused, boolean keyboard) {
        return hovered || (focused && keyboard);
    }

    public static Bounds selectedUnderlay(Bounds tabBounds) {
        Objects.requireNonNull(tabBounds, "tabBounds");
        if (tabBounds.width() <= 4 || tabBounds.height() <= 4) {
            throw new IllegalArgumentException("tab bounds are too small for an underlay");
        }
        return new Bounds(
                tabBounds.x() + 2,
                tabBounds.y() + 2,
                tabBounds.width() - 2,
                tabBounds.height() - 4);
    }

    public static Bounds rightEdge(Bounds tabBounds) {
        Objects.requireNonNull(tabBounds, "tabBounds");
        int width = Math.min(DIVIDER_OVERLAP, tabBounds.width());
        return new Bounds(tabBounds.right() - width, tabBounds.y(), width, tabBounds.height());
    }

    public static List<Bounds> backgroundSegments(Bounds panelBounds) {
        Objects.requireNonNull(panelBounds, "panelBounds");
        int separatorWidth = Math.min(DIVIDER_OVERLAP, panelBounds.width());
        if (panelBounds.width() <= separatorWidth) {
            return List.of();
        }
        return List.of(new Bounds(
                panelBounds.x() + separatorWidth,
                panelBounds.y(),
                panelBounds.width() - separatorWidth,
                panelBounds.height()));
    }

    public static boolean isSelected(ViewSpec.Widget widget) {
        Objects.requireNonNull(widget, "widget");
        return widget.kind() == ViewSpec.WidgetKind.TAB_BUTTON
                && widget.value().filter("selected"::equals).isPresent();
    }

    public static java.util.Optional<Bounds> selectedBounds(ViewSpec view) {
        Objects.requireNonNull(view, "view");
        return view.widgets().stream()
                .filter(VerticalTabStyle::isSelected)
                .map(ViewSpec.Widget::bounds)
                .findFirst();
    }

    public static List<Bounds> separatorSegments(Bounds panelBounds, Bounds selectedTabBounds) {
        Objects.requireNonNull(panelBounds, "panelBounds");
        Objects.requireNonNull(selectedTabBounds, "selectedTabBounds");
        int width = Math.min(DIVIDER_OVERLAP, panelBounds.width());
        Bounds opening = selectedUnderlay(selectedTabBounds);
        int gapTop = Math.max(panelBounds.y(), Math.min(panelBounds.bottom(), opening.y()));
        int gapBottom = Math.max(gapTop, Math.min(panelBounds.bottom(), opening.bottom()));
        List<Bounds> segments = new ArrayList<>(2);
        if (gapTop > panelBounds.y()) {
            segments.add(new Bounds(panelBounds.x(), panelBounds.y(), width, gapTop - panelBounds.y()));
        }
        if (gapBottom < panelBounds.bottom()) {
            segments.add(new Bounds(panelBounds.x(), gapBottom, width, panelBounds.bottom() - gapBottom));
        }
        return List.copyOf(segments);
    }
}
