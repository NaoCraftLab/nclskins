package com.naocraftlab.skins.runtime;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface OptiFineAccountLinkUseCases {
    boolean preparing();
    boolean ready();
    boolean expired();
    URI readyUri(UUID expectedAccount);
    void cancel();
    CompletableFuture<Result> begin(UUID account);

    enum Outcome { READY, AUTH_REQUIRED, FAILED, CANCELLED, EXPIRED }

    record Result(Outcome outcome) {
        public Result { Objects.requireNonNull(outcome, "outcome"); }
    }
}
