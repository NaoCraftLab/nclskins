package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.api.ResponseSchemaCode;
import java.time.Duration;
import java.util.Objects;


public record SessionFailureContext(
        SessionCheckPhase phase,
        ApiFailureKind kind,
        Integer httpStatusCode,
        ResponseSchemaCode responseSchemaCode,
        Duration retryAfter) {

    public SessionFailureContext(
            SessionCheckPhase phase,
            ApiFailureKind kind,
            Integer httpStatusCode) {
        this(phase, kind, httpStatusCode, null, null);
    }

    public SessionFailureContext {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(kind, "kind");
        if (httpStatusCode != null && (httpStatusCode < 100 || httpStatusCode > 599)) {
            throw new IllegalArgumentException("httpStatusCode is outside the HTTP status range");
        }
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter cannot be negative");
        }
    }

    public String safeDiagnostic() {
        String diagnostic = "phase=" + phase.diagnosticName()
                + ", kind=" + kind
                + ", http=" + (httpStatusCode == null ? "none" : httpStatusCode);
        if (responseSchemaCode != null) {
            diagnostic += ", schema=" + responseSchemaCode.diagnosticName();
        }
        if (retryAfter != null) {
            diagnostic += ", retry_after_seconds=" + Math.max(0L, retryAfter.toSeconds());
        }
        return diagnostic;
    }
}
