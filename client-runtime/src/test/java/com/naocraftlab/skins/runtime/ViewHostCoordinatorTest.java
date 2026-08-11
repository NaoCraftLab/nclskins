package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;


class ViewHostCoordinatorTest {
    @Test
    void rebuildsOnlyForNativeShapeChangesAndConsumesFocusOnce() {
        ViewHostCoordinator coordinator = new ViewHostCoordinator();
        ViewSpec first = view("First", new ViewSpec.FocusRequest("name", 7));

        var initial = coordinator.synchronize(first);
        assertTrue(initial.rebuildWidgets());
        assertTrue(initial.rebuildTabs());
        assertTrue(initial.focusRequest().isPresent());
        coordinator.acknowledgeFocus(initial.focusRequest().orElseThrow());

        var labelOnly = coordinator.synchronize(view("Renamed", new ViewSpec.FocusRequest("name", 7)));
        assertFalse(labelOnly.rebuildWidgets());
        assertFalse(labelOnly.rebuildTabs());
        assertTrue(labelOnly.focusRequest().isEmpty());

        coordinator.resetNativeState();
        assertTrue(coordinator.synchronize(first).rebuildWidgets());
    }

    private static ViewSpec view(String label, ViewSpec.FocusRequest focus) {
        Bounds bounds = new Bounds(4, 5, 120, 20);
        return new ViewSpec(
                "characterization",
                UiMessage.literal("Title", UiMessage.Severity.INFO),
                320,
                240,
                List.of(),
                List.of(),
                List.of(ViewSpec.Widget.textField(
                        "name",
                        bounds,
                        UiMessage.literal(label, UiMessage.Severity.INFO),
                        "",
                        UiMessage.literal("Hint", UiMessage.Severity.INFO),
                        true,
                        32)),
                List.of(),
                Optional.empty(),
                List.of(new ViewSpec.TabGroup(
                        "tabs",
                        new Bounds(0, 0, 200, 24),
                        List.of(new ViewSpec.Tab(
                                "one", UiMessage.literal("One", UiMessage.Severity.INFO), true, true)))),
                Optional.of(focus));
    }
}
