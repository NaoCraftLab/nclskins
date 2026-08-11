package com.naocraftlab.skins.server.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerRefreshPolicyTest {
    @Test
    void defaultsMatchThePortableScaleContract() {
        ServerRefreshPolicy small = ServerRefreshPolicy.defaults(false, 1_000);
        ServerRefreshPolicy large = ServerRefreshPolicy.defaults(true, 4_000);

        assertFalse(small.trustedProxyForwarding());
        assertEquals(2_048, small.maxPendingConnections());
        assertEquals(4_256, large.maxPendingConnections());
        assertTrue(large.trustedProxyForwarding());
        assertEquals(2, small.maxConcurrentLookups());
        assertEquals(10.0d, small.lookupRatePerSecond());
        assertEquals(20, small.lookupBurst());
        assertEquals(
                List.of(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10)),
                small.attemptOffsets());
        assertEquals(Duration.ofMinutes(5), small.maxQueueAge());
        assertEquals(Duration.ofSeconds(30), small.lookupCycleDeadline());
        assertEquals(Duration.ofSeconds(5), small.independentCycleCooldown());
        assertEquals(Duration.ofMillis(50), small.batchWindow());
        assertEquals(64, small.maxBatchActors());
        assertEquals(2, small.maxReconciliationAttempts());
        assertEquals(64, small.maxPacketEntries());
        assertEquals(4_096, small.maxRecipientProfileDeliveriesPerTick());
        assertEquals(Duration.ofMillis(5), small.maxPlatformThreadTimePerTick());
    }

    @Test
    void invalidBoundsAndOffsetsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(0, 2, List.of(Duration.ofMillis(1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(10, 0, List.of(Duration.ofMillis(1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(
                        10,
                        2,
                        List.of(Duration.ofMillis(2), Duration.ofMillis(1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerRefreshPolicy.defaults(false, -1));
    }

    private static ServerRefreshPolicy policy(
            int capacity,
            int concurrency,
            List<Duration> attempts) {
        return new ServerRefreshPolicy(
                false,
                capacity,
                concurrency,
                10.0d,
                20,
                attempts,
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofMillis(50),
                64,
                2);
    }
}
