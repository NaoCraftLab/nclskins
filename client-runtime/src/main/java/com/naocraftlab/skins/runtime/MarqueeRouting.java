package com.naocraftlab.skins.runtime;

import java.util.Objects;
import java.util.function.Predicate;


public final class MarqueeRouting {
    private MarqueeRouting() {
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
