package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CatalogGenerationTrackerTest {
    @Test
    void advancesOnlyForManagerPackIdentityOrOrderChanges() {
        CatalogGenerationTracker tracker = new CatalogGenerationTracker();
        Object manager = new Object();
        Object first = new String("same-value");
        Object equalButReloaded = new String("same-value");

        assertEquals(1L, tracker.observe(manager, List.of(first), List.of("pack")));
        assertEquals(1L, tracker.observe(manager, List.of(first), List.of("pack")));
        assertEquals(2L, tracker.observe(manager, List.of(equalButReloaded), List.of("pack")));
        assertEquals(3L, tracker.observe(manager, List.of(equalButReloaded), List.of("other")));
        assertEquals(4L, tracker.observe(new Object(), List.of(equalButReloaded), List.of("other")));
    }
}
