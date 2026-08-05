package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ViewHostPolicyTest {
    @Test
    void lastVisibleClippedWidgetOwnsOverlappingPointer() {
        ViewSpec.Widget lower = widget("lower", new Bounds(0, 0, 20, 20));
        ViewSpec.Widget upper = widget("upper", new Bounds(5, 5, 20, 20));
        ViewSpec view = view(List.of(lower, upper), List.of(
                new ViewSpec.ClipRegion(
                        "upper-clip", new Bounds(10, 10, 5, 5), List.of("upper"))));

        assertEquals("lower", ViewHostPolicy.pointerOwnerAt(view, 7, 7).orElseThrow().id());
        assertEquals("upper", ViewHostPolicy.pointerOwnerAt(view, 12, 12).orElseThrow().id());
        assertFalse(ViewHostPolicy.pointerInsideClip(view, "upper", 7, 7));
    }

    @Test
    void submitRequiresFocusedNonBlankFieldAndEnabledVisibleAction() {
        ViewSpec.Widget field = new ViewSpec.Widget(
                "field", ViewSpec.WidgetKind.TEXT_FIELD, new Bounds(0, 0, 20, 10),
                UiMessage.literal("field", UiMessage.Severity.INFO), Optional.of("value"),
                Optional.empty(), true, true, 32, true, Optional.of("submit"));
        ViewSpec.Widget submit = widget("submit", new Bounds(0, 12, 20, 10));
        ViewSpec view = view(List.of(field, submit), List.of());

        assertEquals(Optional.of("submit"), ViewHostPolicy.submitAction(
                view, "field", true, " value "));
        assertTrue(ViewHostPolicy.submitAction(view, "field", true, "  ").isEmpty());
        assertTrue(ViewHostPolicy.submitAction(view, "field", false, "value").isEmpty());
        assertTrue(ViewHostPolicy.shouldSelectAll(view, "field", true, "value"));
        assertFalse(ViewHostPolicy.shouldSelectAll(view, "field", true, ""));
    }

    private static ViewSpec.Widget widget(String id, Bounds bounds) {
        return new ViewSpec.Widget(
                id, ViewSpec.WidgetKind.BUTTON, bounds,
                UiMessage.literal(id, UiMessage.Severity.INFO), Optional.empty(),
                Optional.empty(), true, true, 0);
    }

    private static ViewSpec view(
            List<ViewSpec.Widget> widgets, List<ViewSpec.ClipRegion> clips) {
        return new ViewSpec(
                "test", UiMessage.literal("test", UiMessage.Severity.INFO), 100, 100,
                List.of(), List.of(), widgets, List.of(), Optional.empty(), List.of(),
                Optional.empty(), clips, List.of());
    }
}
