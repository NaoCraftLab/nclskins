package com.naocraftlab.skins.client;

import com.naocraftlab.skins.client.SignedProfileResolver.ResolvedProfile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;


@FunctionalInterface
public interface PlayerAppearanceSink<P> {
    default void providerVisibility(ProviderVisibility visibility) {}

    ApplyResult apply(ResolvedProfile<P> resolvedProfile);


    default ApplyResult reattach(ExpectedAppearance expectedAppearance) {
        Objects.requireNonNull(expectedAppearance, "expectedAppearance");
        return ApplyResult.DEFERRED;
    }


    default ApplyResult reset(ExpectedAppearance expectedAppearance) {
        Objects.requireNonNull(expectedAppearance, "expectedAppearance");
        return ApplyResult.DEFERRED;
    }


    default void invalidate(ExpectedAppearance expectedAppearance) {
        Objects.requireNonNull(expectedAppearance, "expectedAppearance");
    }

    default List<TrackedCapePlayer> trackedCapePlayers() {
        return List.of();
    }

    default Optional<String> registerCapeTexture(UUID profileId, CapeSource provider,
            String sha256, byte[] normalizedPng) {
        return Optional.empty();
    }

    default void releaseCapeTexture(UUID profileId, CapeSource provider) {}

    enum CapeSource {
        OFFLINE, MINECRAFT, OPTIFINE, SKINMC
    }

    record TrackedCapePlayer(UUID profileId, String canonicalName) {
        public TrackedCapePlayer {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(canonicalName, "canonicalName");
        }
    }

    enum ApplyResult {
        UPDATED,
        DEFERRED
    }
}
