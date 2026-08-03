package com.naocraftlab.skins.core.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;


final class RateLimitGate {
    static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);
    static final Duration MAX_COOLDOWN = Duration.ofHours(24);

    private final Clock clock;
    private final AtomicReference<Instant> blockedUntil = new AtomicReference<>();

    RateLimitGate(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Optional<Duration> remaining() {
        Instant blocked = blockedUntil.get();
        if (blocked == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!now.isBefore(blocked)) {
            blockedUntil.compareAndSet(blocked, null);
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, blocked));
    }

    Duration remember(String retryAfter) {
        Duration cooldown = parse(retryAfter, clock.instant()).orElse(DEFAULT_COOLDOWN);
        remember(cooldown);
        return bound(cooldown);
    }

    void remember(Duration cooldown) {
        Instant candidate = clock.instant().plus(bound(cooldown));
        blockedUntil.accumulateAndGet(
                candidate,
                (current, update) -> current == null || update.isAfter(current) ? update : current);
    }

    static Optional<Duration> parse(String value, Instant now) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim();
        try {
            long seconds = Long.parseLong(normalized);
            return Optional.of(bound(Duration.ofSeconds(Math.max(0, seconds))));
        } catch (NumberFormatException | ArithmeticException ignored) {
            try {
                Instant target = ZonedDateTime.parse(
                        normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration duration = target.isAfter(now) ? Duration.between(now, target) : Duration.ZERO;
                return Optional.of(bound(duration));
            } catch (DateTimeParseException | ArithmeticException invalidDate) {
                return Optional.empty();
            }
        }
    }

    private static Duration bound(Duration duration) {
        Duration checked = Objects.requireNonNull(duration, "duration");
        if (checked.isNegative()) {
            return DEFAULT_COOLDOWN;
        }
        return checked.compareTo(MAX_COOLDOWN) > 0 ? MAX_COOLDOWN : checked;
    }
}
