package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface AppearanceRefresh extends AutoCloseable {
    CompletableFuture<Result> afterMutation(PresetApplicationOutcome outcome, Consumer<Result> publisher);
    CompletableFuture<Result> afterReconnect(AppliedAppearance appearance, Consumer<Result> publisher);
    void providerVisibility(com.naocraftlab.skins.client.ProviderVisibility visibility);
    void close();

    public enum Result {
        UPDATED,
        DEFERRED,
        SUPERSEDED,
        NOT_APPLICABLE
    }
}
