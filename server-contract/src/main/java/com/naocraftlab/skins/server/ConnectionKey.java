package com.naocraftlab.skins.server;

import java.util.Objects;
import java.util.UUID;


public record ConnectionKey(UUID profileId, long generation) {
    public ConnectionKey {
        Objects.requireNonNull(profileId, "profileId");
        if (generation < 0L) {
            throw new IllegalArgumentException("Connection generation must not be negative");
        }
    }


    @Override
    public String toString() {
        return "ConnectionKey[redacted]";
    }
}
