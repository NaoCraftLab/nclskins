package com.naocraftlab.skins.server;


public record PublicationMetrics(
        long recipients,
        long profileDeliveries,
        long packetChunks,
        long watcherPairs,
        long estimatedEgressBytes,
        long platformThreadNanos,
        long platformThreadMaxTickNanos,
        long reconciliationAttempts,
        long reconciliationDeliveries) {
    public static final PublicationMetrics ZERO =
            new PublicationMetrics(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

    public PublicationMetrics {
        if (recipients < 0L || profileDeliveries < 0L || packetChunks < 0L
                || watcherPairs < 0L || estimatedEgressBytes < 0L
                || platformThreadNanos < 0L || platformThreadMaxTickNanos < 0L
                || reconciliationAttempts < 0L
                || reconciliationDeliveries < 0L) {
            throw new IllegalArgumentException("Publication metrics must not be negative");
        }
    }
}
