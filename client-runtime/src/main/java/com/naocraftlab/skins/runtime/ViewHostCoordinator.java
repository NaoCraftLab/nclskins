package com.naocraftlab.skins.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ViewHostCoordinator {
    private List<WidgetShape> widgets = List.of();
    private List<TabShape> tabs = List.of();
    private final FocusRequestLedger focusRequests = new FocusRequestLedger();

    public Synchronization synchronize(ViewSpec view) {
        Objects.requireNonNull(view, "view");
        List<WidgetShape> nextWidgets = view.widgets().stream().map(WidgetShape::of).toList();
        List<TabShape> nextTabs = view.tabGroups().stream().map(TabShape::of).toList();
        boolean rebuildWidgets = !widgets.equals(nextWidgets);
        boolean rebuildTabs = !tabs.equals(nextTabs);
        widgets = nextWidgets;
        tabs = nextTabs;
        Optional<ViewSpec.FocusRequest> focus = focusRequests.pending(view);
        return new Synchronization(rebuildWidgets, rebuildTabs, focus);
    }

    public void acknowledgeFocus(String screenId, ViewSpec.FocusRequest request) {
        Objects.requireNonNull(screenId, "screenId");
        Objects.requireNonNull(request, "request");
        focusRequests.acknowledge(screenId, request);
    }

    public void resetNativeState() {
        widgets = List.of();
        tabs = List.of();
    }

    public void resetFocusSession() {
        focusRequests.reset();
    }

    public record Synchronization(
            boolean rebuildWidgets,
            boolean rebuildTabs,
            Optional<ViewSpec.FocusRequest> focusRequest) {
        public Synchronization {
            focusRequest = Objects.requireNonNull(focusRequest, "focusRequest");
        }
    }

    private record WidgetShape(
            String id,
            ViewSpec.WidgetKind kind,
            Bounds bounds,
            int maxLength,
            boolean selectAllOnFocusAcquire,
            Optional<String> submitActionId) {
        private static WidgetShape of(ViewSpec.Widget widget) {
            return new WidgetShape(
                    widget.id(),
                    widget.kind(),
                    widget.bounds(),
                    widget.maxLength(),
                    widget.selectAllOnFocusAcquire(),
                    widget.submitActionId());
        }
    }

    private record TabShape(String id, Bounds bounds, List<String> tabIds) {
        private static TabShape of(ViewSpec.TabGroup group) {
            return new TabShape(
                    group.id(), group.bounds(), group.tabs().stream().map(ViewSpec.Tab::id).toList());
        }
    }
}
