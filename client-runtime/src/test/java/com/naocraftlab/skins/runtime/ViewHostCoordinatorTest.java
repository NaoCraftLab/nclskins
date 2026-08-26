package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ViewHostCoordinatorTest {
    @Test
    void rebuildsOnlyForNativeShapeChangesAndConsumesFocusOnce() {
        ViewHostCoordinator coordinator = new ViewHostCoordinator();
        ViewSpec first = view("First", new ViewSpec.FocusRequest("name", 7));

        var initial = coordinator.synchronize(first);
        assertTrue(initial.rebuildWidgets());
        assertTrue(initial.rebuildTabs());
        assertTrue(initial.focusRequest().isPresent());
        coordinator.acknowledgeFocus(first.screenId(), initial.focusRequest().orElseThrow());

        var labelOnly = coordinator.synchronize(view("Renamed", new ViewSpec.FocusRequest("name", 7)));
        assertFalse(labelOnly.rebuildWidgets());
        assertFalse(labelOnly.rebuildTabs());
        assertTrue(labelOnly.focusRequest().isEmpty());

        coordinator.resetNativeState();
        var restored = coordinator.synchronize(first);
        assertTrue(restored.rebuildWidgets());
        assertTrue(restored.focusRequest().isEmpty());

        ViewSpec runtimeOverride = view("First", new ViewSpec.FocusRequest("card:second-row", 8));
        var runtimeFocus = coordinator.synchronize(runtimeOverride);
        assertTrue(runtimeFocus.focusRequest().isPresent());
        coordinator.acknowledgeFocus(runtimeOverride.screenId(), runtimeFocus.focusRequest().orElseThrow());
        assertTrue(coordinator.synchronize(first).focusRequest().isEmpty(),
                "an older presenter request must stay consumed after a runtime override");

        ViewSpec nextScreen = view("other", "First", new ViewSpec.FocusRequest("name", 7));
        assertTrue(coordinator.synchronize(nextScreen).focusRequest().isPresent());

        coordinator.acknowledgeFocus(nextScreen.screenId(), nextScreen.focusRequest().orElseThrow());
        coordinator.resetFocusSession();
        assertTrue(coordinator.synchronize(nextScreen).focusRequest().isPresent());
    }

    @Test
    void ledgerRetainsOnlyRequestValuesAndResetsWhenTheScreenChanges() {
        FocusRequestLedger ledger = new FocusRequestLedger();
        ViewSpec first = view("first", "First", new ViewSpec.FocusRequest("name", 1));
        ViewSpec secondRequest = view("first", "First", new ViewSpec.FocusRequest("card", 2));

        ledger.acknowledge(first.screenId(), first.focusRequest().orElseThrow());
        ledger.acknowledge(secondRequest.screenId(), secondRequest.focusRequest().orElseThrow());
        assertEquals(2, ledger.appliedCount());
        assertTrue(ledger.pending(first).isEmpty());

        ViewSpec nextScreen = view("second", "First", first.focusRequest().orElseThrow());
        assertTrue(ledger.pending(nextScreen).isPresent());
        assertEquals(0, ledger.appliedCount());
    }

    private static ViewSpec view(String label, ViewSpec.FocusRequest focus) {
        return view("characterization", label, focus);
    }

    private static ViewSpec view(String screenId, String label, ViewSpec.FocusRequest focus) {
        Bounds bounds = new Bounds(4, 5, 120, 20);
        return new ViewSpec(
                screenId,
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
