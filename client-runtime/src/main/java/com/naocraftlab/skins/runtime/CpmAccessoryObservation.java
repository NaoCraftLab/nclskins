package com.naocraftlab.skins.runtime;

import java.util.Objects;

public record CpmAccessoryObservation(long generation, Availability availability,
        boolean cape, boolean elytra) {
    public enum Availability { UNKNOWN, PENDING, FAILED, BLOCKED, CONFIRMED }

    public CpmAccessoryObservation {
        Objects.requireNonNull(availability);
        if (generation < 0 || availability != Availability.CONFIRMED && (cape || elytra))
            throw new IllegalArgumentException("Invalid accessory observation");
    }

    public boolean empty() { return availability == Availability.CONFIRMED && !cape && !elytra; }
    public boolean present() { return availability == Availability.CONFIRMED && (cape || elytra); }
    public static CpmAccessoryObservation unknown() {
        return new CpmAccessoryObservation(0, Availability.UNKNOWN, false, false);
    }
}
