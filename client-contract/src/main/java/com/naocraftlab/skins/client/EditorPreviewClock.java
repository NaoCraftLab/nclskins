package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.function.LongSupplier;

public final class EditorPreviewClock {
    private static final double NANOS_PER_TICK = 50_000_000.0;

    private final LongSupplier nanoTime;
    private boolean started;
    private long lastNanos;
    private double ageTicks;

    public EditorPreviewClock() {
        this(System::nanoTime);
    }

    EditorPreviewClock(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public float ageTicks(float initialAgeTicks) {
        if (!Float.isFinite(initialAgeTicks)) {
            throw new IllegalArgumentException("Initial preview age must be finite");
        }
        long now = nanoTime.getAsLong();
        if (!started) {
            started = true;
            lastNanos = now;
            ageTicks = initialAgeTicks;
            return initialAgeTicks;
        }
        if (now > lastNanos) {
            ageTicks += (now - lastNanos) / NANOS_PER_TICK;
            lastNanos = now;
        }
        return (float) ageTicks;
    }
}
