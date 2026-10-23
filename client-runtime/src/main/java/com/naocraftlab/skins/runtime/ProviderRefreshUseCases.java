package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public interface ProviderRefreshUseCases extends AutoCloseable {
    CompletableFuture<ProviderRefreshResult> refresh(ProviderRefreshRequest request, AppearanceProviders before);
    boolean refreshing(ProviderRefreshRequest request, AppearanceProviders before);
    void initialPublished(ProviderRefreshResult result);
    void publicationRejected(ProviderRefreshResult result);
    void invalidate();
    void close();

    record Scope(ProviderRefreshRequest request, GameSessionTokenSource.SessionIdentity identity,
            AppearanceProviders before, List<BuiltinProvider> providers, long capeGeneration) {
        public Scope {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(before, "before");
            providers = List.copyOf(providers);
        }

        public boolean configurationMatches(AppearanceProviders current) {
            return before.skin().configurationRevision() == current.skin().configurationRevision()
                    && before.cape().configurationRevision() == current.cape().configurationRevision()
                    && before.skin().order().equals(current.skin().order())
                    && before.cape().order().equals(current.cape().order());
        }
    }
}
