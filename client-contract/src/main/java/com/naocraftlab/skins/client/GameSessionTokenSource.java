package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.UUID;


public interface GameSessionTokenSource {
    SessionIdentity currentSession();

    <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E;


    default <T, E extends Exception> T withSession(SessionRequest<T, E> request) throws E {
        Objects.requireNonNull(request, "request");
        SessionIdentity identity = Objects.requireNonNull(currentSession(), "current session");
        return withAccessToken(accessToken -> request.execute(identity, accessToken));
    }

    @FunctionalInterface
    interface TokenRequest<T, E extends Exception> {
        T execute(String accessToken) throws E;
    }

    @FunctionalInterface
    interface SessionRequest<T, E extends Exception> {
        T execute(SessionIdentity identity, String accessToken) throws E;
    }

    record SessionIdentity(UUID profileId, String profileName) {
        public SessionIdentity {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(profileName, "profileName");
        }
    }
}
