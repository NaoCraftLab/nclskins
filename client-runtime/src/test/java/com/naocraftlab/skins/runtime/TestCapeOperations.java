package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

interface TestCapeOperations extends ClientOperations {
    default void startOptiFineCapes() {}

    default void refreshOptiFineCapes() {}

    default void refreshOptiFineCapes(Consumer<ProviderObservation<ProviderCape>> completion) {
        refreshOptiFineCapes();
        completion.accept(null);
    }

    default void refreshSkinMcCapes(Consumer<ProviderObservation<ProviderCape>> completion) {
        completion.accept(null);
    }

    default Optional<Duration> capeProviderCooldown(BuiltinProvider provider) {
        return Optional.empty();
    }

    default void optiFineConfigurationChanged() {}

    default void adoptSharedCapeObservation(UUID accountId, String canonicalName,
            AppearanceProviders providers) {}

    default void onCapeObservation(Consumer<Observation> listener) {}

    default void selfCapeCandidatesChanged(UUID accountId, String canonicalName,
            AppearanceProviders providers) {}

    default void trackedCapePlayer(UUID profileId, String canonicalName) {}

    default void untrackedCapePlayer(UUID profileId) {}

    default void capeWorldChanged() {}

    default void closeOptiFineCapes() {}

}
