package com.naocraftlab.skins.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;


public final class MarqueeRouting {
    private MarqueeRouting() {
    }

    public static Optional<Bounds> movingClip(ViewSpec view, ViewSpec.Text text) {
        Bounds clip = text.marqueeActivation().flatMap(ViewSpec.MarqueeActivation::movingClip).orElse(text.bounds());
        var viewport = view.clipFor(text.id());
        if (viewport.isEmpty()) return Optional.of(clip);
        Bounds v = viewport.orElseThrow();
        int left = Math.max(clip.x(), v.x());
        int top = Math.max(clip.y(), v.y());
        int width = Math.min(clip.right(), v.right()) - left;
        int height = Math.min(clip.bottom(), v.bottom()) - top;
        if (width <= 0 || height <= 0) return Optional.empty();
        return Optional.of(new Bounds(left, top, width, height));
    }

    public static boolean active(
            ViewSpec view,
            ViewSpec.Text text,
            double mouseX,
            double mouseY,
            Predicate<String> focusedWidget) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(focusedWidget, "focusedWidget");
        return text.marqueeActivation().map(activation -> {
            boolean insideClip = view.clipFor(text.id())
                    .map(bounds -> bounds.contains(mouseX, mouseY))
                    .orElse(true);
            boolean hovered = insideClip && activation.hoverBounds().contains(mouseX, mouseY);
            boolean focused = activation.focusWidgetIds().stream().anyMatch(focusedWidget);
            return hovered || focused;
        }).orElse(false);
    }
}
