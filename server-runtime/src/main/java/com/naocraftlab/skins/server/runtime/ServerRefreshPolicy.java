package com.naocraftlab.skins.server.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Objects;


public final class ServerRefreshPolicy {
    private static final int MIN_PENDING_CONNECTIONS = 2_048;

    private final boolean trustedProxyForwarding;
    private final int maxPendingConnections;
    private final int maxConcurrentLookups;
    private final double lookupRatePerSecond;
    private final int lookupBurst;
    private final List<Duration> attemptOffsets;
    private final Duration maxQueueAge;
    private final Duration lookupCycleDeadline;
    private final Duration independentCycleCooldown;
    private final Duration batchWindow;
    private final int maxBatchActors;
    private final int maxReconciliationAttempts;
    private final int maxPacketEntries;
    private final int maxRecipientProfileDeliveriesPerTick;
    private final Duration maxPlatformThreadTimePerTick;

    public static ServerRefreshPolicy defaults(
            boolean trustedProxyForwarding,
            int serverMaxPlayers) {
        if (serverMaxPlayers < 0) {
            throw new IllegalArgumentException("Server maximum players must not be negative");
        }
        return new ServerRefreshPolicy(
                trustedProxyForwarding,
                Math.max(MIN_PENDING_CONNECTIONS, saturatedAdd(serverMaxPlayers, 256)),
                2,
                10.0d,
                20,
                List.of(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10)),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofMillis(50),
                64,
                2);
    }

    public ServerRefreshPolicy(
            boolean trustedProxyForwarding,
            int maxPendingConnections,
            int maxConcurrentLookups,
            double lookupRatePerSecond,
            int lookupBurst,
            List<Duration> attemptOffsets,
            Duration maxQueueAge,
            Duration lookupCycleDeadline,
            Duration independentCycleCooldown,
            Duration batchWindow,
            int maxBatchActors,
            int maxReconciliationAttempts) {
        this(
                trustedProxyForwarding,
                maxPendingConnections,
                maxConcurrentLookups,
                lookupRatePerSecond,
                lookupBurst,
                attemptOffsets,
                maxQueueAge,
                lookupCycleDeadline,
                independentCycleCooldown,
                batchWindow,
                maxBatchActors,
                maxReconciliationAttempts,
                64,
                4_096,
                Duration.ofMillis(5));
    }

    public ServerRefreshPolicy(
            boolean trustedProxyForwarding,
            int maxPendingConnections,
            int maxConcurrentLookups,
            double lookupRatePerSecond,
            int lookupBurst,
            List<Duration> attemptOffsets,
            Duration maxQueueAge,
            Duration lookupCycleDeadline,
            Duration independentCycleCooldown,
            Duration batchWindow,
            int maxBatchActors,
            int maxReconciliationAttempts,
            int maxPacketEntries,
            int maxRecipientProfileDeliveriesPerTick,
            Duration maxPlatformThreadTimePerTick) {
        this.trustedProxyForwarding = trustedProxyForwarding;
        this.maxPendingConnections = requirePositive(
                maxPendingConnections, "Maximum pending connections");
        this.maxConcurrentLookups = requirePositive(
                maxConcurrentLookups, "Maximum concurrent lookups");
        if (!Double.isFinite(lookupRatePerSecond) || lookupRatePerSecond <= 0.0d) {
            throw new IllegalArgumentException("Lookup rate must be finite and positive");
        }
        this.lookupRatePerSecond = lookupRatePerSecond;
        this.lookupBurst = requirePositive(lookupBurst, "Lookup burst");
        Objects.requireNonNull(attemptOffsets, "attemptOffsets");
        if (attemptOffsets.isEmpty()) {
            throw new IllegalArgumentException("At least one lookup attempt is required");
        }
        this.attemptOffsets = attemptOffsets.stream()
                .map(offset -> requireNonNegative(offset, "Attempt offset"))
                .toList();
        for (int index = 1; index < this.attemptOffsets.size(); index++) {
            if (this.attemptOffsets.get(index).compareTo(this.attemptOffsets.get(index - 1)) <= 0) {
                throw new IllegalArgumentException("Attempt offsets must increase strictly");
            }
        }
        this.maxQueueAge = requirePositive(maxQueueAge, "Maximum queue age");
        this.lookupCycleDeadline = requirePositive(
                lookupCycleDeadline, "Lookup cycle deadline");
        this.independentCycleCooldown = requireNonNegative(
                independentCycleCooldown, "Independent cycle cooldown");
        this.batchWindow = requireNonNegative(batchWindow, "Batch window");
        this.maxBatchActors = requirePositive(maxBatchActors, "Maximum batch actors");
        if (maxReconciliationAttempts < 0) {
            throw new IllegalArgumentException("Reconciliation attempts must not be negative");
        }
        this.maxReconciliationAttempts = maxReconciliationAttempts;
        this.maxPacketEntries = requirePositive(maxPacketEntries, "Maximum packet entries");
        this.maxRecipientProfileDeliveriesPerTick = requirePositive(
                maxRecipientProfileDeliveriesPerTick,
                "Maximum recipient-profile deliveries per tick");
        this.maxPlatformThreadTimePerTick = requirePositive(
                maxPlatformThreadTimePerTick,
                "Maximum platform thread time per tick");
    }

    public boolean trustedProxyForwarding() {
        return trustedProxyForwarding;
    }

    public int maxPendingConnections() {
        return maxPendingConnections;
    }

    public int maxConcurrentLookups() {
        return maxConcurrentLookups;
    }

    public double lookupRatePerSecond() {
        return lookupRatePerSecond;
    }

    public int lookupBurst() {
        return lookupBurst;
    }

    public List<Duration> attemptOffsets() {
        return attemptOffsets;
    }

    public Duration maxQueueAge() {
        return maxQueueAge;
    }

    public Duration lookupCycleDeadline() {
        return lookupCycleDeadline;
    }

    public Duration independentCycleCooldown() {
        return independentCycleCooldown;
    }

    public Duration batchWindow() {
        return batchWindow;
    }

    public int maxBatchActors() {
        return maxBatchActors;
    }

    public int maxReconciliationAttempts() {
        return maxReconciliationAttempts;
    }

    public int maxPacketEntries() {
        return maxPacketEntries;
    }

    public int maxRecipientProfileDeliveriesPerTick() {
        return maxRecipientProfileDeliveriesPerTick;
    }

    public Duration maxPlatformThreadTimePerTick() {
        return maxPlatformThreadTimePerTick;
    }

    @Override
    public String toString() {
        return "ServerRefreshPolicy[trustedProxyForwarding=" + trustedProxyForwarding
                + ", maxPendingConnections=" + maxPendingConnections
                + ", maxConcurrentLookups=" + maxConcurrentLookups + ']';
    }

    private static int saturatedAdd(int left, int right) {
        return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    private static int requirePositive(int value, String label) {
        if (value <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String label) {
        Duration checked = Objects.requireNonNull(value, label);
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return checked;
    }

    private static Duration requireNonNegative(Duration value, String label) {
        Duration checked = Objects.requireNonNull(value, label);
        if (checked.isNegative()) {
            throw new IllegalArgumentException(label + " must not be negative");
        }
        return checked;
    }
}
