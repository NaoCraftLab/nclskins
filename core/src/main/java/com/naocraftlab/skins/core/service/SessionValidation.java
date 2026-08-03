package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.model.RemoteProfile;
import java.util.Objects;
import java.util.Optional;


public record SessionValidation(
        SessionStatus status,
        GameSessionTokenSource.SessionIdentity sessionIdentity,
        RemoteProfile profile,
        SessionFailureContext failureContext,
        String userMessage) {

    public SessionValidation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sessionIdentity, "sessionIdentity");
        Objects.requireNonNull(userMessage, "userMessage");
    }

    public boolean valid() {
        return status == SessionStatus.VALID;
    }

    public Optional<RemoteProfile> optionalProfile() {
        return Optional.ofNullable(profile);
    }

    public ApiFailureKind failureKind() {
        return failureContext == null ? null : failureContext.kind();
    }
}
