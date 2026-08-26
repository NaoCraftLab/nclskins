package com.naocraftlab.skins.runtime;

import java.util.Objects;

public final class VanillaListSurface {
    private VanillaListSurface() {}

    public static Sample sample(ViewSpec view, ViewSpec.Panel panel) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(panel, "panel");
        ViewSpec.ScrollSurface surface = view.navigationNode(panel.id())
                .flatMap(ViewSpec.NavigationNode::surfaceId)
                .flatMap(view::scrollSurface)
                .or(() -> view.scrollSurfaces().stream().findFirst())
                .orElse(null);
        int offset = surface == null ? 0 : (int) surface.offsetPixels();
        int u = panel.bounds().right();
        int v = panel.bounds().bottom();
        if (surface != null) {
            if (surface.orientation() == ViewSpec.Scrollbar.Orientation.HORIZONTAL) {
                u += offset;
            } else {
                v += offset;
            }
        }
        return new Sample(u, v);
    }

    public static Boundaries boundaries(Bounds bounds) {
        Objects.requireNonNull(bounds, "bounds");
        if (bounds.height() < 2) {
            return new Boundaries(bounds.y(), bounds.y());
        }
        return new Boundaries(bounds.y(), bounds.bottom() - 2);
    }

    public record Sample(int u, int v) {}

    public record Boundaries(int topY, int bottomY) {}
}
