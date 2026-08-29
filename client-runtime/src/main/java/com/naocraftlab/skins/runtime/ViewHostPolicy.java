package com.naocraftlab.skins.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;


public final class ViewHostPolicy {
    public static boolean pointerInsideClip(
            ViewSpec view, String elementId, double pointerX, double pointerY) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(elementId, "elementId");
        return view.clipFor(elementId)
                .map(bounds -> bounds.contains(pointerX, pointerY))
                .orElse(true);
    }

    public static Optional<ViewSpec.Widget> pointerOwnerAt(
            ViewSpec view, double pointerX, double pointerY) {
        Objects.requireNonNull(view, "view");
        ViewSpec.Widget owner = null;
        for (ViewSpec.Widget widget : view.widgets()) {
            if (widget.visible()
                    && widget.bounds().contains(pointerX, pointerY)
                    && pointerInsideClip(view, widget.id(), pointerX, pointerY)) {
                owner = widget;
            }
        }
        return Optional.ofNullable(owner);
    }

    public static boolean compositeCardHovered(
            ViewSpec view, String widgetId, double pointerX, double pointerY) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(widgetId, "widgetId");
        return view.widget(widgetId)
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.CATALOG_CARD)
                .filter(ViewSpec.Widget::visible)
                .filter(widget -> widget.bounds().contains(pointerX, pointerY))
                .filter(widget -> pointerInsideClip(
                        view, widget.id(), pointerX, pointerY))
                .isPresent();
    }

    public static Optional<String> submitAction(
            ViewSpec view,
            String focusedWidgetId,
            boolean nativeFieldFocused,
            String nativeValue) {
        Objects.requireNonNull(view, "view");
        if (focusedWidgetId == null || !nativeFieldFocused || nativeValue == null
                || nativeValue.trim().isEmpty()) {
            return Optional.empty();
        }
        Optional<String> actionId = view.widget(focusedWidgetId)
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.TEXT_FIELD)
                .filter(ViewSpec.Widget::enabled)
                .filter(ViewSpec.Widget::visible)
                .flatMap(ViewSpec.Widget::submitActionId);
        return actionId.filter(id -> view.widget(id)
                .filter(ViewSpec.Widget::visible)
                .filter(ViewSpec.Widget::enabled)
                .isPresent());
    }

    public static boolean shouldSelectAllOnFocusAcquire(
            ViewSpec view,
            String widgetId,
            FocusCause cause,
            boolean wasFocused,
            boolean nativeFieldFocused,
            String nativeValue) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(cause, "cause");
        return cause != FocusCause.RESTORE
                && !wasFocused
                && nativeFieldFocused
                && nativeValue != null
                && !nativeValue.isEmpty()
                && view.widget(widgetId)
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.TEXT_FIELD)
                .filter(ViewSpec.Widget::selectAllOnFocusAcquire)
                .filter(ViewSpec.Widget::enabled)
                .filter(ViewSpec.Widget::visible)
                .isPresent();
    }

    public enum FocusCause {
        POINTER,
        KEYBOARD,
        PROGRAMMATIC,
        RESTORE
    }

    public static List<WidgetShape> widgetShapes(ViewSpec view) {
        Objects.requireNonNull(view, "view");
        return view.widgets().stream().map(WidgetShape::new).toList();
    }

    public record WidgetShape(
            String id,
            ViewSpec.WidgetKind kind,
            Optional<GuiIcon> icon,
            boolean visible,
            int maxLength,
            boolean selectAllOnFocusAcquire,
            Optional<String> submitActionId) {
        public WidgetShape(ViewSpec.Widget widget) {
            this(
                    widget.id(),
                    widget.kind(),
                    widget.icon(),
                    widget.visible(),
                    widget.maxLength(),
                    widget.selectAllOnFocusAcquire(),
                    widget.submitActionId());
        }
    }

    private ViewHostPolicy() {
    }
}
