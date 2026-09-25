package com.naocraftlab.skins.runtime;

import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class SkinMcFreshnessQuery {
    private static final String CAPE_PREFIX = "https://skinmc.net/api/v1/skinmcCape/";
    private static final AtomicLong LAST = new AtomicLong();

    private final Clock clock;

    SkinMcFreshnessQuery(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    static URI canonical(UUID profileId) {
        return URI.create(CAPE_PREFIX + Objects.requireNonNull(profileId, "profileId"));
    }

    long next() {
        while (true) {
            long previous = LAST.get();
            if (previous == Long.MAX_VALUE) throw new IllegalStateException("SkinMC freshness value exhausted");
            long candidate = Math.max(Math.max(clock.millis(), 1L), previous + 1L);
            if (LAST.compareAndSet(previous, candidate)) return candidate;
        }
    }

    static URI wire(UUID profileId, URI canonical, long freshness) {
        if (canonical == null) throw invalid();
        URI wire = URI.create(canonical.toASCIIString() + "?t=" + freshness);
        requireExact(profileId, canonical, wire, freshness);
        return wire;
    }

    static void requireExact(UUID profileId, URI canonical, URI wire, long freshness) {
        if (profileId == null || canonical == null || wire == null || freshness < 1) throw invalid();
        String logical = canonical.toASCIIString();
        if (!canonical(profileId).toASCIIString().equals(logical)) throw invalid();
        if (!wire.toASCIIString().equals(logical + "?t=" + freshness)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid SkinMC cape request");
    }
}
