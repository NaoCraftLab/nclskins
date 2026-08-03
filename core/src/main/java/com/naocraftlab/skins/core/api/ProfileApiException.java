package com.naocraftlab.skins.core.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;


public final class ProfileApiException extends Exception {
    private static final long serialVersionUID = 1L;

    private final ApiFailureKind kind;
    private final Integer statusCode;
    private final Duration retryAfter;
    private final boolean mutationMayHaveApplied;
    private final ResponseSchemaCode responseSchemaCode;

    public ProfileApiException(
            ApiFailureKind kind,
            String safeMessage,
            Integer statusCode,
            Duration retryAfter,
            boolean mutationMayHaveApplied) {
        this(kind, safeMessage, statusCode, retryAfter, mutationMayHaveApplied, null);
    }

    public ProfileApiException(
            ApiFailureKind kind,
            String safeMessage,
            Integer statusCode,
            Duration retryAfter,
            boolean mutationMayHaveApplied,
            ResponseSchemaCode responseSchemaCode) {
        super(safeMessage);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
        this.mutationMayHaveApplied = mutationMayHaveApplied;
        this.responseSchemaCode = responseSchemaCode;
    }

    public ApiFailureKind kind() {
        return kind;
    }

    public OptionalInt statusCode() {
        return statusCode == null ? OptionalInt.empty() : OptionalInt.of(statusCode);
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public boolean mutationMayHaveApplied() {
        return mutationMayHaveApplied;
    }

    public Optional<ResponseSchemaCode> responseSchemaCode() {
        return Optional.ofNullable(responseSchemaCode);
    }

    public boolean sessionExpired() {
        return kind == ApiFailureKind.SESSION_EXPIRED;
    }
}
