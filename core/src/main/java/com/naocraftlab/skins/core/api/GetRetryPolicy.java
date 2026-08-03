package com.naocraftlab.skins.core.api;

import java.time.Duration;
import java.util.Objects;


public record GetRetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
    public GetRetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 5");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (initialBackoff.isNegative() || maxBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("retry delays are invalid");
        }
    }

    public static GetRetryPolicy defaults() {
        return new GetRetryPolicy(3, Duration.ofMillis(250), Duration.ofSeconds(5));
    }

    public Duration exponentialBackoff(int completedAttempts) {
        if (completedAttempts < 1) {
            throw new IllegalArgumentException("completedAttempts must be positive");
        }
        long multiplier = 1L << Math.min(completedAttempts - 1, 20);
        Duration candidate;
        try {
            candidate = initialBackoff.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            return maxBackoff;
        }
        return candidate.compareTo(maxBackoff) > 0 ? maxBackoff : candidate;
    }
}
