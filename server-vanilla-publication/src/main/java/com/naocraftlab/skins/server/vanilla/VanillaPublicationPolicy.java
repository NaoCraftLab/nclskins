package com.naocraftlab.skins.server.vanilla;

import java.time.Duration;
import java.util.Objects;


public record VanillaPublicationPolicy(
        int maxBatchActors,
        int maxPacketEntries,
        int maxRecipientProfileDeliveriesPerTick,
        Duration maxPlatformThreadTimePerTick,
        int maxReconciliationAttempts,
        int maxReconciliationDeliveries) {
    public VanillaPublicationPolicy {
        if (maxBatchActors < 1
                || maxPacketEntries < 1
                || maxRecipientProfileDeliveriesPerTick < 1
                || maxReconciliationAttempts < 0
                || maxReconciliationDeliveries < 0) {
            throw new IllegalArgumentException("Publication limits must be positive");
        }
        Objects.requireNonNull(maxPlatformThreadTimePerTick, "maxPlatformThreadTimePerTick");
        if (maxPlatformThreadTimePerTick.isZero()
                || maxPlatformThreadTimePerTick.isNegative()) {
            throw new IllegalArgumentException("Platform-thread budget must be positive");
        }
    }
}
