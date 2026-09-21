package com.naocraftlab.skins.runtime;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class NativeResourceMaintenance implements AutoCloseable {
    public static final int FALLBACK_TICKS = 20;

    private final BooleanSupplier active;
    private final Runnable inspection;
    private long generation;
    private int eligibleTicks;
    private boolean dirty = true;
    private boolean playerWasReady;
    private boolean closed;

    public NativeResourceMaintenance(BooleanSupplier active, Runnable inspection) {
        this.active = Objects.requireNonNull(active, "active");
        this.inspection = Objects.requireNonNull(inspection, "inspection");
    }

    public synchronized void markDirty() {
        if (!closed) {
            generation++;
            dirty = true;
        }
    }

    public synchronized void tick(boolean playerReady) {
        if (closed) {
            return;
        }
        if (!playerReady) {
            playerWasReady = false;
            return;
        }
        if (!playerWasReady) {
            generation++;
            dirty = true;
            playerWasReady = true;
        }
        if (!active.getAsBoolean()) {
            dirty = false;
            eligibleTicks = 0;
            return;
        }
        eligibleTicks++;
        if (!dirty && eligibleTicks < FALLBACK_TICKS) {
            return;
        }
        long inspectedGeneration = generation;
        inspection.run();
        eligibleTicks = 0;
        if (generation == inspectedGeneration) {
            dirty = false;
        }
    }

    public synchronized boolean closed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        closed = true;
        dirty = false;
        eligibleTicks = 0;
        playerWasReady = false;
    }
}
