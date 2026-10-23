package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.UUID;

public record PreviewAccessories(UUID scope, long generation, Availability availability,
        boolean cape, boolean elytra, PreviewRenderer.LiveReason liveReason) {
    public enum Availability { UNKNOWN, PENDING, FAILED, BLOCKED, CONFIRMED }

    public PreviewAccessories {
        Objects.requireNonNull(availability);
        Objects.requireNonNull(liveReason);
        if (generation < 0 || availability != Availability.CONFIRMED && (cape || elytra)
                || availability == Availability.CONFIRMED && scope == null)
            throw new IllegalArgumentException("Invalid preview accessories");
    }

    public boolean present() { return availability == Availability.CONFIRMED && (cape || elytra); }
    public boolean canInspect() {
        return availability == Availability.CONFIRMED && liveReason == PreviewRenderer.LiveReason.AVAILABLE;
    }
    public static PreviewAccessories unavailable(PreviewRenderer.LiveReason reason) {
        return new PreviewAccessories(null, 0, Availability.UNKNOWN, false, false, reason);
    }
}
