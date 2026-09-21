package com.naocraftlab.skins.core.provider;

import java.util.Objects;

public record ProviderDelivery(long intentRevision, long activation, Status status) {
    public enum Status {
        IDLE, PENDING, ATTEMPTING, CONFIRMED, UNKNOWN
    }

    public ProviderDelivery {
        if (intentRevision < 0 || activation < 0) {
            throw new IllegalArgumentException("Provider delivery revisions cannot be negative");
        }
        Objects.requireNonNull(status, "status");
    }

    public ProviderDelivery assign(long revision, long generation) {
        return new ProviderDelivery(revision, generation,
                status == Status.UNKNOWN || status == Status.ATTEMPTING
                        ? Status.UNKNOWN : Status.PENDING);
    }

    public ProviderDelivery withStatus(Status replacement) {
        return new ProviderDelivery(intentRevision, activation, replacement);
    }
}
