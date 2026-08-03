package com.naocraftlab.skins.server;


public record ServerRefreshHealthSnapshot(
        int pending,
        int ready,
        int retryWaiting,
        int lookupsInFlight,
        int publicationsInFlight,
        long accepted,
        long coalesced,
        long overloaded,
        long expired,
        long throttled,
        long lookups,
        long lookupResolved,
        long lookupTransientFailures,
        long lookupRejected,
        long lookupLatencyTotalNanos,
        long lookupLatencyMaxNanos,
        long oldestQueueAgeNanos,
        long globalThrottleRemainingNanos,
        long publicationBatches,
        long publicationActors,
        long publicationRecipients,
        long publicationProfileDeliveries,
        long publicationPacketChunks,
        long publicationWatcherPairs,
        long estimatedEgressBytes,
        long platformThreadTotalNanos,
        long platformThreadMaxNanos,
        long reconciliationAttempts,
        long reconciliationDeliveries) {
    public ServerRefreshHealthSnapshot {
        if (pending < 0 || ready < 0 || retryWaiting < 0 || lookupsInFlight < 0
                || publicationsInFlight < 0 || accepted < 0L || coalesced < 0L
                || overloaded < 0L || expired < 0L || throttled < 0L || lookups < 0L
                || lookupResolved < 0L || lookupTransientFailures < 0L || lookupRejected < 0L
                || lookupLatencyTotalNanos < 0L || lookupLatencyMaxNanos < 0L
                || oldestQueueAgeNanos < 0L || globalThrottleRemainingNanos < 0L
                || publicationBatches < 0L || publicationActors < 0L
                || publicationRecipients < 0L || publicationProfileDeliveries < 0L
                || publicationPacketChunks < 0L || publicationWatcherPairs < 0L
                || estimatedEgressBytes < 0L || platformThreadTotalNanos < 0L
                || platformThreadMaxNanos < 0L || reconciliationAttempts < 0L
                || reconciliationDeliveries < 0L) {
            throw new IllegalArgumentException("Refresh metrics must not be negative");
        }
    }
}
