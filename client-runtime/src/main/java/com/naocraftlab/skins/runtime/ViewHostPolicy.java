package com.naocraftlab.skins.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;


public final class ViewHostPolicy {
    private static final List<String> PROVIDER_ROW_WIDGET_KINDS =
            List.of("row", "up", "down", "edit", "remove", "account");

    public static boolean belongsToProviderRow(String elementId, String provider) {
        if (elementId == null || provider == null || provider.isEmpty()
                || provider.indexOf('.') >= 0) {
            return false;
        }
        for (String kind : PROVIDER_ROW_WIDGET_KINDS) {
            if (elementId.equals("providers." + kind + "." + provider)) {
                return true;
            }
        }
        String capabilityPrefix = "providers.capability." + provider + ".";
        if (!elementId.startsWith(capabilityPrefix)) {
            return false;
        }
        String capability = elementId.substring(capabilityPrefix.length());
        return !capability.isEmpty() && capability.indexOf('.') < 0;
    }

    public static boolean visibleIntersection(ViewSpec view, String elementId) {
        ViewSpec.Widget widget = view.widget(elementId).orElseThrow();
        return view.clipFor(elementId).map(clip ->
                widget.bounds().x() < clip.right()
                        && widget.bounds().right() > clip.x()
                        && widget.bounds().y() < clip.bottom()
                        && widget.bounds().bottom() > clip.y()).orElse(true);
    }

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

    public static Optional<PassiveIndicatorTooltip> passiveIndicatorTooltip(
            ViewSpec view, double pointerX, double pointerY, String focusedWidgetId) {
        Objects.requireNonNull(view, "view");
        Optional<ViewSpec.Widget> hovered = pointerOwnerAt(view, pointerX, pointerY)
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.PASSIVE_INDICATOR)
                .filter(ViewSpec.Widget::enabled)
                .filter(widget -> visibleIntersection(view, widget.id()));
        if (hovered.isPresent()) {
            return hovered.map(widget -> new PassiveIndicatorTooltip(widget, true));
        }
        return Optional.ofNullable(focusedWidgetId)
                .flatMap(view::widget)
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.PASSIVE_INDICATOR)
                .filter(ViewSpec.Widget::visible)
                .filter(ViewSpec.Widget::enabled)
                .filter(widget -> visibleIntersection(view, widget.id()))
                .map(widget -> new PassiveIndicatorTooltip(widget, false));
    }

    public record PassiveIndicatorTooltip(ViewSpec.Widget widget, boolean hovered) {
        public List<UiMessage> lines() {
            UiMessage title = widget.label();
            return widget.hint().filter(description -> !description.equals(title))
                    .map(description -> List.of(title, description))
                    .orElseGet(() -> List.of(title));
        }

        public int x(double pointerX) {
            return hovered ? (int) pointerX : widget.bounds().x() + widget.bounds().width() / 2;
        }

        public int y(double pointerY) {
            return hovered ? (int) pointerY : widget.bounds().y() + widget.bounds().height() / 2;
        }
    }

    public static Optional<String> inlineCapePointerActionAt(
            ViewSpec view, double pointerX, double pointerY) {
        return pointerOwnerAt(view, pointerX, pointerY)
                .filter(ViewSpec.Widget::enabled)
                .filter(widget -> widget.id().startsWith("editor.cape_action."))
                .filter(widget -> widget.kind() == ViewSpec.WidgetKind.BUTTON
                        || widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON
                        || widget.kind() == ViewSpec.WidgetKind.ICON_ONLY_BUTTON)
                .map(ViewSpec.Widget::id);
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
            Optional<WidgetIcon> icon,
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
