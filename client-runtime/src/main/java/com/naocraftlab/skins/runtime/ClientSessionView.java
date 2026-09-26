package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.GameSessionTokenSource;

import java.time.Duration;
import java.util.Optional;

public interface ClientSessionView {
    boolean rateLimited();

    Optional<Duration> rateLimitRemaining();

    GameSessionTokenSource.SessionIdentity sessionIdentity();
}
