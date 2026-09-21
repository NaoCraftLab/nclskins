package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeResourceMaintenanceTest {
    @Test
    void dirtyRunsOnceOnNextEligibleTick() {
        AtomicInteger inspections = new AtomicInteger();
        NativeResourceMaintenance maintenance =
                new NativeResourceMaintenance(() -> true, inspections::incrementAndGet);

        maintenance.tick(false);
        maintenance.tick(true);
        maintenance.tick(true);

        assertEquals(1, inspections.get());
    }

    @Test
    void stableStateUsesBoundedFallback() {
        AtomicInteger inspections = new AtomicInteger();
        NativeResourceMaintenance maintenance =
                new NativeResourceMaintenance(() -> true, inspections::incrementAndGet);
        maintenance.tick(true);

        for (int tick = 1; tick < NativeResourceMaintenance.FALLBACK_TICKS; tick++) {
            maintenance.tick(true);
        }
        assertEquals(1, inspections.get());

        maintenance.tick(true);
        assertEquals(2, inspections.get());
    }

    @Test
    void inactiveOverrideDoesNotInspectOrAccumulateFallback() {
        AtomicBoolean active = new AtomicBoolean();
        AtomicInteger inspections = new AtomicInteger();
        NativeResourceMaintenance maintenance =
                new NativeResourceMaintenance(active::get, inspections::incrementAndGet);
        for (int tick = 0; tick < 40; tick++) {
            maintenance.tick(true);
        }
        active.set(true);
        maintenance.markDirty();
        maintenance.tick(true);

        assertEquals(1, inspections.get());
    }

    @Test
    void reentrantDirtySurvivesInspectionGeneration() {
        NativeResourceMaintenance[] holder = new NativeResourceMaintenance[1];
        AtomicInteger inspections = new AtomicInteger();
        holder[0] = new NativeResourceMaintenance(() -> true, () -> {
            if (inspections.incrementAndGet() == 1) {
                holder[0].markDirty();
            }
        });

        holder[0].tick(true);
        holder[0].tick(true);

        assertEquals(2, inspections.get());
    }

    @Test
    void closeDropsDirtyAndTrailingTicks() {
        AtomicInteger inspections = new AtomicInteger();
        NativeResourceMaintenance maintenance =
                new NativeResourceMaintenance(() -> true, inspections::incrementAndGet);

        maintenance.close();
        maintenance.markDirty();
        maintenance.tick(true);

        assertEquals(0, inspections.get());
        assertTrue(maintenance.closed());
    }
}
