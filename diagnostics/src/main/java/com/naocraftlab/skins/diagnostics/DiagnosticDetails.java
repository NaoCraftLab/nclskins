package com.naocraftlab.skins.diagnostics;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;


public final class DiagnosticDetails {
    private static final DiagnosticDetails NONE = new DiagnosticDetails(
            null, null, null, null, null);

    private final DiagnosticStatus status;
    private final Long count;
    private final Integer attempt;
    private final Duration duration;
    private final SanitizedFailure failure;

    private DiagnosticDetails(
            DiagnosticStatus status,
            Long count,
            Integer attempt,
            Duration duration,
            SanitizedFailure failure) {
        if (count != null && count < 0L) {
            throw new IllegalArgumentException("count must not be negative");
        }
        if (attempt != null && attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
        if (duration != null && duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        this.status = status;
        this.count = count;
        this.attempt = attempt;
        this.duration = duration;
        this.failure = failure;
    }

    public static DiagnosticDetails none() {
        return NONE;
    }

    public static DiagnosticDetails failure(Throwable failure) {
        return new DiagnosticDetails(
                null, null, null, null, SanitizedFailure.from(failure));
    }

    public static DiagnosticDetails status(DiagnosticStatus status) {
        return new DiagnosticDetails(
                Objects.requireNonNull(status, "status"), null, null, null, null);
    }

    public static DiagnosticDetails statusFailure(
            DiagnosticStatus status, Throwable failure) {
        return new DiagnosticDetails(
                Objects.requireNonNull(status, "status"),
                null,
                null,
                null,
                SanitizedFailure.from(failure));
    }

    public static DiagnosticDetails attemptFailure(int attempt, Throwable failure) {
        return new DiagnosticDetails(
                null, null, attempt, null, SanitizedFailure.from(failure));
    }

    public static DiagnosticDetails count(long count) {
        return new DiagnosticDetails(null, count, null, null, null);
    }

    public static DiagnosticDetails duration(Duration duration) {
        return new DiagnosticDetails(
                null, null, null, Objects.requireNonNull(duration, "duration"), null);
    }

    public Optional<DiagnosticStatus> status() {
        return Optional.ofNullable(status);
    }

    public OptionalLong count() {
        return count == null ? OptionalLong.empty() : OptionalLong.of(count);
    }

    public OptionalLong attempt() {
        return attempt == null ? OptionalLong.empty() : OptionalLong.of(attempt);
    }

    public Optional<Duration> duration() {
        return Optional.ofNullable(duration);
    }

    public Optional<SanitizedFailure> failure() {
        return Optional.ofNullable(failure);
    }
}
