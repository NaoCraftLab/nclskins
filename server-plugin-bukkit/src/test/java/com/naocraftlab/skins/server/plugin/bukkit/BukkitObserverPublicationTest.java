package com.naocraftlab.skins.server.plugin.bukkit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class BukkitObserverPublicationTest {
    @Test
    void performsObserverPublicationAtomicallyInPacketOrder() {
        List<String> calls = new ArrayList<>();
        AtomicBoolean tracking = new AtomicBoolean(true);

        BukkitObserverPublication.refresh(
                () -> {
                    calls.add("untrack");
                    tracking.set(false);
                },
                () -> calls.add("player-info"),
                () -> {
                    calls.add("retrack");
                    tracking.set(true);
                },
                tracking::get);

        assertEquals(List.of("untrack", "player-info", "retrack"), calls);
    }

    @Test
    void restoresTrackingBeforePropagatingPlayerInfoFailure() {
        AtomicBoolean tracking = new AtomicBoolean(true);
        IllegalStateException expected = new IllegalStateException("packet failure");

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> BukkitObserverPublication.refresh(
                        () -> tracking.set(false),
                        () -> { throw expected; },
                        () -> tracking.set(true),
                        tracking::get));

        assertEquals(expected, actual);
        assertTrue(tracking.get());
    }

    @Test
    void rejectsSilentFoliaRetrackNoOp() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BukkitObserverPublication.refresh(
                        () -> { },
                        () -> { },
                        () -> { },
                        () -> false));

        assertEquals("Observer tracking was not restored", failure.getMessage());
    }

    @Test
    void preservesPublicationFailureWhenRetrackingAlsoFails() {
        IllegalStateException publication = new IllegalStateException("publication");
        IllegalStateException tracking = new IllegalStateException("tracking");
        List<String> calls = new ArrayList<>();

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> BukkitObserverPublication.refresh(
                        () -> calls.add("untrack"),
                        () -> { calls.add("publish"); throw publication; },
                        () -> { calls.add("retrack"); throw tracking; },
                        () -> { calls.add("check"); return true; }));

        assertEquals(tracking, actual);
        assertEquals(List.of(publication), List.of(actual.getSuppressed()));
        assertEquals(List.of("untrack", "publish", "retrack"), calls);
    }

    @Test
    void failedUntrackDoesNotStartLaterPublicationOperations() {
        IllegalStateException expected = new IllegalStateException("untrack");
        List<String> calls = new ArrayList<>();

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> BukkitObserverPublication.refresh(
                        () -> { throw expected; },
                        () -> calls.add("publish"),
                        () -> calls.add("retrack"),
                        () -> { calls.add("check"); return true; }));

        assertEquals(expected, actual);
        assertEquals(List.of(), calls);
    }
}
