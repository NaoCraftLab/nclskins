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
        assertTrue(ViewHostPolicy.shouldSelectAllOnFocusAcquire(
                view, "field", ViewHostPolicy.FocusCause.KEYBOARD, false, true, "value"));
        assertTrue(ViewHostPolicy.shouldSelectAllOnFocusAcquire(
                view, "field", ViewHostPolicy.FocusCause.POINTER, false, true, "value"));
        assertTrue(ViewHostPolicy.shouldSelectAllOnFocusAcquire(
                view, "field", ViewHostPolicy.FocusCause.PROGRAMMATIC, false, true, "value"));
        assertFalse(ViewHostPolicy.shouldSelectAllOnFocusAcquire(
                view, "field", ViewHostPolicy.FocusCause.POINTER, true, true, "value"));
        assertFalse(ViewHostPolicy.shouldSelectAllOnFocusAcquire(
                view, "field", ViewHostPolicy.FocusCause.RESTORE, false, true, "value"));
        assertFalse(ViewHostPolicy.shouldSelectAllOnFocusAcquire(
                view, "field", ViewHostPolicy.FocusCause.POINTER, false, true, ""));
    }

    @Test
    void compositeCardKeepsHoverWhenChildActionOwnsPointer() {
        ViewSpec.Widget card = new ViewSpec.Widget(
                "card", ViewSpec.WidgetKind.CATALOG_CARD, new Bounds(0, 0, 40, 40),
                UiMessage.literal("card", UiMessage.Severity.INFO), Optional.empty(),
                Optional.empty(), true, true, 0);
        ViewSpec.Widget action = widget("card.action", new Bounds(20, 20, 18, 18));
        ViewSpec view = view(List.of(card, action), List.of());

        assertEquals("card.action", ViewHostPolicy.pointerOwnerAt(view, 25, 25)
                .orElseThrow().id());
        assertTrue(ViewHostPolicy.compositeCardHovered(view, "card", 25, 25));
        assertFalse(ViewHostPolicy.compositeCardHovered(view, "card", 45, 25));
        assertFalse(ViewHostPolicy.compositeCardHovered(view, "card.action", 25, 25));
    }

    @Test
    void topmostInlineCapeActionIsDispatchedDirectlyOverItsCard() {
        ViewSpec.Widget card = new ViewSpec.Widget(
                "editor.cape_item.OFFLINE.cape", ViewSpec.WidgetKind.CAPE_CARD,
                new Bounds(0, 0, 80, 100),
                UiMessage.literal("cape", UiMessage.Severity.INFO), Optional.empty(),
                Optional.empty(), true, true, 0);
        ViewSpec.Widget save = widget(
                "editor.cape_action.save.cape", new Bounds(2, 78, 37, 20));
        ViewSpec.Widget cancel = widget(
                "editor.cape_action.cancel.cape", new Bounds(41, 78, 37, 20));
        ViewSpec.Widget field = new ViewSpec.Widget(
                "editor.cape_action.name", ViewSpec.WidgetKind.TEXT_FIELD,
                new Bounds(2, 2, 76, 20),
                UiMessage.literal("name", UiMessage.Severity.INFO), Optional.of("Renamed"),
                Optional.empty(), true, true, 32, true, Optional.of(save.id()));
        ViewSpec view = view(List.of(card, field, save, cancel), List.of());

        assertEquals(Optional.of(save.id()),
                ViewHostPolicy.inlineCapePointerActionAt(view, 20, 88));
        assertEquals(Optional.of(cancel.id()),
                ViewHostPolicy.inlineCapePointerActionAt(view, 60, 88));
        assertTrue(ViewHostPolicy.inlineCapePointerActionAt(view, 20, 40).isEmpty());
        assertEquals(Optional.of(save.id()),
                ViewHostPolicy.submitAction(view, field.id(), true, "Renamed"));
    }

    @Test
    void nativeIconRemainsPartOfTheWidgetShapeWithoutChangingPointerOwnership() {
        ViewSpec.Widget accept = ViewSpec.Widget.iconButton(
                "delete.confirm", new Bounds(2, 2, 20, 20),
                UiMessage.info("nclskins.your_skins.delete_confirm"),
                NativeGuiIcon.ACCEPT, true);
        ViewSpec view = view(List.of(accept), List.of());

        assertEquals(Optional.of(NativeGuiIcon.ACCEPT),
                ViewHostPolicy.widgetShapes(view).get(0).icon());
        assertEquals(Optional.of(accept), ViewHostPolicy.pointerOwnerAt(view, 10, 10));
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
                Optional.of(new ViewSpec.FocusRequest(widgets.get(0).id(), 1L)), clips, List.of());
    }
}
