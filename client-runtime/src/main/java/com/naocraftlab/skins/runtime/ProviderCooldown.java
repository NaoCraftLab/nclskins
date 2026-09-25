package com.naocraftlab.skins.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

final class ProviderCooldown {
    private static final Duration MIN = Duration.ofSeconds(1);
    private static final Duration DEFAULT = Duration.ofSeconds(60);
    private static final Duration MAX = Duration.ofHours(24);

    private final Clock clock;
    private final AtomicReference<Instant> until = new AtomicReference<>();

    ProviderCooldown(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Optional<Duration> remaining() {
        Instant current = until.get();
        if (current == null) return Optional.empty();
        Instant now = clock.instant();
        if (!now.isBefore(current)) {
            until.compareAndSet(current, null);
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, current));
    }

    void observe(int status, Map<String, List<String>> headers) {
        Instant now = clock.instant();
        Duration retryAfter = parseRetryAfter(header(headers, "Retry-After"), now).orElse(null);
        Optional<Duration> quotaReset = "0".equals(header(headers, "X-RateLimit-Remaining"))
                ? parseReset(header(headers, "X-RateLimit-Reset"), now) : Optional.empty();
        if (status == 429) {
            remember(retryAfter != null ? retryAfter : quotaReset.orElse(DEFAULT), now);
            return;
        }
        quotaReset.ifPresent(value -> remember(value, now));
    }

    void clear() {
        until.set(null);
    }

    private void remember(Duration duration, Instant now) {
        Instant candidate = now.plus(bound(duration));
        until.accumulateAndGet(candidate,
                (current, update) -> current == null || update.isAfter(current) ? update : current);
    }

    private static Optional<Duration> parseRetryAfter(String value, Instant now) {
        if (value == null) return Optional.empty();
        String text = value.trim();
        if (text.matches("[0-9]{1,19}")) {
            try {
                return Optional.of(bound(Duration.ofSeconds(Long.parseLong(text))));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        try {
            Instant target = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return target.isAfter(now) ? Optional.of(bound(Duration.between(now, target))) : Optional.empty();
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> parseReset(String value, Instant now) {
        if (value == null || !value.matches("[0-9]{10,19}")) return Optional.empty();
        try {
            Instant reset = Instant.ofEpochSecond(Long.parseLong(value));
            return reset.isAfter(now) ? Optional.of(bound(Duration.between(now, reset))) : Optional.empty();
        } catch (NumberFormatException | java.time.DateTimeException ignored) {
            return Optional.empty();
        }
    }

    private static Duration bound(Duration value) {
        if (value.compareTo(MIN) < 0) return MIN;
        return value.compareTo(MAX) > 0 ? MAX : value;
    }

    private static String header(Map<String, List<String>> headers, String name) {
        if (headers == null) return null;
        String found = null;
        for (var entry : headers.entrySet()) {
            if (!entry.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) continue;
            if (found != null || entry.getValue() == null || entry.getValue().size() != 1) return null;
            found = entry.getValue().get(0);
        }
        return found;
    }
}
