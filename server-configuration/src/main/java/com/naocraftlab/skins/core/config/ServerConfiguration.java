package com.naocraftlab.skins.core.config;

import java.util.Objects;


public record ServerConfiguration(RealtimeRefresh realtimeRefresh) {
    public ServerConfiguration {
        Objects.requireNonNull(realtimeRefresh, "realtimeRefresh");
    }

    public static ServerConfiguration defaults() {
        return new ServerConfiguration(new RealtimeRefresh(
                true,
                false,
                2,
                10.0d,
                20));
    }

    public ServerConfiguration withRealtimeRefreshEnabled(boolean enabled) {
        RealtimeRefresh current = realtimeRefresh;
        return new ServerConfiguration(new RealtimeRefresh(
                enabled,
                current.trustedProxyForwarding(),
                current.maxConcurrentLookups(),
                current.lookupRatePerSecond(),
                current.lookupBurst()));
    }

    public ServerConfiguration withTrustedProxyForwarding(boolean enabled) {
        RealtimeRefresh current = realtimeRefresh;
        return new ServerConfiguration(new RealtimeRefresh(
                current.enabled(),
                enabled,
                current.maxConcurrentLookups(),
                current.lookupRatePerSecond(),
                current.lookupBurst()));
    }

    public ServerConfiguration withMaxConcurrentLookups(int value) {
        RealtimeRefresh current = realtimeRefresh;
        return new ServerConfiguration(new RealtimeRefresh(
                current.enabled(),
                current.trustedProxyForwarding(),
                value,
                current.lookupRatePerSecond(),
                current.lookupBurst()));
    }

    public ServerConfiguration withLookupRatePerSecond(double value) {
        RealtimeRefresh current = realtimeRefresh;
        return new ServerConfiguration(new RealtimeRefresh(
                current.enabled(),
                current.trustedProxyForwarding(),
                current.maxConcurrentLookups(),
                value,
                current.lookupBurst()));
    }

    public ServerConfiguration withLookupBurst(int value) {
        RealtimeRefresh current = realtimeRefresh;
        return new ServerConfiguration(new RealtimeRefresh(
                current.enabled(),
                current.trustedProxyForwarding(),
                current.maxConcurrentLookups(),
                current.lookupRatePerSecond(),
                value));
    }

    public record RealtimeRefresh(
            boolean enabled,
            boolean trustedProxyForwarding,
            int maxConcurrentLookups,
            double lookupRatePerSecond,
            int lookupBurst) {
        public RealtimeRefresh {
            if (maxConcurrentLookups <= 0) {
                throw new IllegalArgumentException("Maximum concurrent lookups must be positive");
            }
            if (!Double.isFinite(lookupRatePerSecond) || lookupRatePerSecond <= 0.0d) {
                throw new IllegalArgumentException("Lookup rate must be finite and positive");
            }
            if (lookupBurst <= 0) {
                throw new IllegalArgumentException("Lookup burst must be positive");
            }
        }
    }
}
