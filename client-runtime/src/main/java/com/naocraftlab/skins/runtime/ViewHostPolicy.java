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

    public static boolean shouldSelectAll(
            ViewSpec view,
            String widgetId,
            boolean nativeFieldFocused,
            String nativeValue) {
        Objects.requireNonNull(view, "view");
        return nativeFieldFocused
                && nativeValue != null
                && !nativeValue.isEmpty()
                && view.widget(widgetId)
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.TEXT_FIELD)
                .filter(ViewSpec.Widget::selectAllOnPrimaryClick)
                .filter(ViewSpec.Widget::enabled)
                .filter(ViewSpec.Widget::visible)
                .isPresent();
    }

    public static Optional<String> focusTargetAfterMouseDispatch(
            ViewSpec view, String currentFocusedWidgetId) {
        Objects.requireNonNull(view, "view");
        return view.focusRequest()
                .map(ViewSpec.FocusRequest::widgetId)
                .filter(widgetId -> !widgetId.equals(currentFocusedWidgetId))
                .filter(widgetId -> view.widget(widgetId)
                        .filter(ViewSpec.Widget::visible)
                        .filter(ViewSpec.Widget::enabled)
                        .isPresent());
    }

    public static List<WidgetShape> widgetShapes(ViewSpec view) {
        Objects.requireNonNull(view, "view");
        return view.widgets().stream().map(WidgetShape::new).toList();
    }

    public record WidgetShape(
            String id,
            ViewSpec.WidgetKind kind,
            Optional<String> icon,
            boolean visible,
            int maxLength,
            boolean selectAllOnPrimaryClick,
            Optional<String> submitActionId) {
        public WidgetShape(ViewSpec.Widget widget) {
            this(
                    widget.id(),
                    widget.kind(),
                    widget.icon(),
                    widget.visible(),
                    widget.maxLength(),
                    widget.selectAllOnPrimaryClick(),
                    widget.submitActionId());
        }
    }

    private ViewHostPolicy() {
    }
}
