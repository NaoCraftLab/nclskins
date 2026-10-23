package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public interface CapeObservationPort {
    void startOptiFineCapes();
    long capeObservationGeneration();
    void refreshCapeProvider(ProviderRefreshUseCases.Scope scope, BuiltinProvider provider,
            Consumer<ProviderObservation<ProviderCape>> completion);
    void selfCapeCandidatesChanged(UUID accountId, String canonicalName, AppearanceProviders providers,
            java.util.Set<BuiltinProvider> allowedSources);
    Optional<Duration> capeProviderCooldown(BuiltinProvider provider);
    void optiFineConfigurationChanged();
    void adoptSharedCapeObservation(UUID accountId, String canonicalName, AppearanceProviders providers);
    void onCapeObservation(Consumer<Observation> listener);
    void selfCapeCandidatesChanged(UUID accountId, String canonicalName, AppearanceProviders providers);
    void trackedCapePlayer(UUID profileId, String canonicalName);
    void untrackedCapePlayer(UUID profileId);
    void capeWorldChanged();
    void closeOptiFineCapes();

    record Observation(BuiltinProvider provider, UUID accountId, String canonicalName,
            long capeConfigurationRevision, String skinSha256,
            ProviderObservation<ProviderCape> observation) {
        public ProviderCape cape() { return observation.value(); }
    }
}
