package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class NativeUiRoutingTest {
    @Test
    void controllerPublishedFramesDoNotResetTheNativeAnimationTarget() {
        NativeScrollSync state = new NativeScrollSync();
        ViewSpec.ScrollSurface initial = surface(0.0, 100.0);

        assertTrue(state.synchronize(Optional.of(initial)).resetOffset());
        state.acceptedRuntimeOffset(12.0);
        NativeScrollSync.Decision publishedFrame =
                state.synchronize(Optional.of(surface(12.0, 100.0)));
        assertFalse(publishedFrame.geometryChanged());
        assertFalse(publishedFrame.resetOffset());

        NativeScrollSync.Decision externalReset =
                state.synchronize(Optional.of(surface(0.0, 100.0)));
        assertTrue(externalReset.resetOffset());
        assertTrue(state.synchronize(Optional.of(surface(0.0, 120.0))).geometryChanged());
        assertFalse(state.synchronize(Optional.empty()).active());
    }

    @Test
    void marqueeRequiresVisibleHoverOrLinkedFocus() {
        ViewSpec.Text text = new ViewSpec.Text(
                "card.name",
                new Bounds(10, 10, 40, 10),
                UiMessage.literal("A very long name", UiMessage.Severity.INFO),
                ViewSpec.Text.Alignment.CENTER,
                Optional.of(new ViewSpec.MarqueeActivation(
                        new Bounds(5, 5, 60, 40), List.of("card.action"))));
        ViewSpec view = new ViewSpec(
                "gallery",
                UiMessage.info("title"),
                100,
                100,
                List.of(),
                List.of(text),
                List.of(),
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(new ViewSpec.ClipRegion(
                        "cards", new Bounds(0, 0, 30, 100), List.of("card."))),
                List.of());

        assertTrue(MarqueeRouting.active(view, text, 20, 20, ignored -> false));
        assertFalse(MarqueeRouting.active(view, text, 40, 20, ignored -> false));
        assertTrue(MarqueeRouting.active(view, text, 90, 90, "card.action"::equals));
        assertFalse(MarqueeRouting.active(view, text, 90, 90, "unrelated"::equals));

        ViewSpec.Text staticText = new ViewSpec.Text(
                "static", new Bounds(0, 0, 10, 10), UiMessage.info("static"),
                ViewSpec.Text.Alignment.LEFT);
        assertTrue(text.marqueeActivation().isPresent());
        assertEquals(Optional.empty(), staticText.marqueeActivation());
        assertEquals(ViewSpec.Text.Layout.SINGLE_LINE, text.layout());
        assertEquals(ViewSpec.Text.Layout.SINGLE_LINE, staticText.layout());
        assertFalse(MarqueeRouting.active(view, staticText, 1, 1, ignored -> true));

        ViewSpec.Text wrapped = new ViewSpec.Text(
                "wrapped",
                new Bounds(0, 0, 40, 30),
                UiMessage.info("wrapped"),
                ViewSpec.Text.Alignment.CENTER,
                ViewSpec.Text.Layout.WRAP);
        assertEquals(ViewSpec.Text.Layout.WRAP, wrapped.layout());
        assertThrows(IllegalArgumentException.class, () -> new ViewSpec.Text(
                "invalid",
                new Bounds(0, 0, 40, 30),
                UiMessage.info("invalid"),
                ViewSpec.Text.Alignment.CENTER,
                Optional.of(new ViewSpec.MarqueeActivation(
                        new Bounds(0, 0, 40, 30), List.of("action"))),
                ViewSpec.Text.Layout.WRAP));
    }

    private static ViewSpec.ScrollSurface surface(double offset, double maximum) {
        return new ViewSpec.ScrollSurface(
                "gallery.cards",
                new Bounds(0, 0, 100, 80),
                ViewSpec.Scrollbar.Orientation.HORIZONTAL,
                offset,
                maximum);
    }
}
