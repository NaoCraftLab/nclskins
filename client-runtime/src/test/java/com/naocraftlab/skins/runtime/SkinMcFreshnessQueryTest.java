package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkinMcFreshnessQueryTest {
    private static final UUID PROFILE = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final UUID OTHER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void fixedClockAndClockRollbackStillProduceProcessWideIncreasingDecimalValues() {
        Clock fixed = Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneOffset.UTC);
        Clock earlier = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
        SkinMcFreshnessQuery first = new SkinMcFreshnessQuery(fixed);
        SkinMcFreshnessQuery second = new SkinMcFreshnessQuery(earlier);
        long a = first.next();
        long b = first.next();
        long c = second.next();
        assertTrue(a > 0 && b > a && c > b);
        URI canonical = SkinMcFreshnessQuery.canonical(PROFILE);
        for (long value : List.of(a, b, c)) {
            URI wire = SkinMcFreshnessQuery.wire(PROFILE, canonical, value);
            assertEquals("t=" + value, wire.getRawQuery());
            SkinMcFreshnessQuery.requireExact(PROFILE, canonical, wire, value);
        }
    }

    @Test
    void transportRejectsCanonicalAndWireSubstitutionBeforePhysicalGet() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OptifineCapeReader.Transport transport = (uri, timeout, maxBytes) -> {
            calls.incrementAndGet();
            return new OptifineCapeReader.Response(404, new byte[0]);
        };
        URI canonical = SkinMcFreshnessQuery.canonical(PROFILE);
        long freshness = 123456L;
        URI validWire = SkinMcFreshnessQuery.wire(PROFILE, canonical, freshness);
        transport.getSkinMcCape(PROFILE, canonical, validWire, freshness, Duration.ofSeconds(10), 1000);
        assertEquals(1, calls.get());

        for (URI malicious : List.of(
                URI.create("https://skinmc.net/api/v1/skinmcCape/" + OTHER),
                URI.create("https://skinmc.net/api/v1/skinmcCape/" + PROFILE + "?source=other"),
                URI.create("https://skinmc.net/api/v1/skinmcCape/../" + PROFILE),
                URI.create("https://skinmc.net/api/v1/skinmcCape/%2e%2e/" + PROFILE),
                URI.create("https://skinmc.net:443/api/v1/skinmcCape/" + PROFILE),
                URI.create("https://user@skinmc.net/api/v1/skinmcCape/" + PROFILE),
                URI.create("https://example.org/api/v1/skinmcCape/" + PROFILE))) {
            assertThrows(IllegalArgumentException.class, () -> transport.getSkinMcCape(
                    PROFILE, malicious, validWire, freshness, Duration.ofSeconds(10), 1000));
        }
        for (URI malicious : List.of(
                URI.create("https://skinmc.net/api/v1/skinmcCape/" + OTHER + "?t=" + freshness),
                URI.create(canonical + "?t=" + (freshness + 1)),
                URI.create(canonical + "?t=" + freshness + "&x=1"),
                URI.create(canonical + "?x=1&t=" + freshness),
                URI.create(canonical + "?t=%31" + freshness),
                URI.create(canonical + "?t=" + freshness + "#fragment"),
                URI.create("https://example.org/api/v1/skinmcCape/" + PROFILE + "?t=" + freshness),
                URI.create("https://skinmc.net/api/v1/skinmcCape/%2e%2e/" + PROFILE + "?t=" + freshness))) {
            assertThrows(IllegalArgumentException.class, () -> transport.getSkinMcCape(
                    PROFILE, canonical, malicious, freshness, Duration.ofSeconds(10), 1000));
        }
        assertEquals(1, calls.get(), "rejected URLs never reach physical GET");
    }

    @Test
    void differentProfileCannotReuseOtherwiseValidCanonicalWirePair() {
        URI canonical = SkinMcFreshnessQuery.canonical(PROFILE);
        URI wire = SkinMcFreshnessQuery.wire(PROFILE, canonical, 123L);
        assertThrows(IllegalArgumentException.class, () ->
                SkinMcFreshnessQuery.requireExact(OTHER, canonical, wire, 123L));
        assertThrows(IllegalArgumentException.class, () ->
                SkinMcFreshnessQuery.requireExact(PROFILE, canonical, wire, 0L));
    }
}
