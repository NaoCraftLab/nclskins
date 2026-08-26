package com.naocraftlab.skins.runtime;

import java.util.Objects;
import java.util.Optional;

public final class NativeScrollSync {
    private ViewSpec.ScrollSurface surface;
    private double acceptedRuntimeOffset = Double.NaN;

    public Decision synchronize(Optional<ViewSpec.ScrollSurface> desired) {
        Objects.requireNonNull(desired, "desired");
        if (desired.isEmpty()) {
            surface = null;
            acceptedRuntimeOffset = Double.NaN;
            return Decision.CLEAR;
        }
        ViewSpec.ScrollSurface next = desired.orElseThrow();
        boolean geometryChanged = surface == null
                || !surface.id().equals(next.id())
                || !surface.viewport().equals(next.viewport())
                || surface.orientation() != next.orientation()
                || Double.compare(surface.maximumPixels(), next.maximumPixels()) != 0
                || Double.compare(surface.wheelStepPixels(), next.wheelStepPixels()) != 0;
        boolean externalOffset = Double.isFinite(acceptedRuntimeOffset)
                && Math.abs(acceptedRuntimeOffset - next.offsetPixels()) > 0.001;
        surface = next;
        acceptedRuntimeOffset = next.offsetPixels();
        return new Decision(
                true,
                geometryChanged,
                geometryChanged || externalOffset);
    }

    public void acceptedRuntimeOffset(double offsetPixels) {
        if (!Double.isFinite(offsetPixels)) {
            throw new IllegalArgumentException("accepted native scroll offset must be finite");
        }
        acceptedRuntimeOffset = offsetPixels;
    }

    public record Decision(boolean active, boolean geometryChanged, boolean resetOffset) {
        private static final Decision CLEAR = new Decision(false, false, false);
    }
}
