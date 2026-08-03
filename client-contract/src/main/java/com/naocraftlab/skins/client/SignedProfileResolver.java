package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;


@FunctionalInterface
public interface SignedProfileResolver<P> {
    CompletableFuture<Optional<ResolvedProfile<P>>> resolve(ExpectedAppearance expected);


    record ResolvedProfile<P>(
            UUID profileId,
            ExpectedAppearance expectedAppearance,
            P platformProfile) {
        public ResolvedProfile {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(expectedAppearance, "expectedAppearance");
            Objects.requireNonNull(platformProfile, "platformProfile");
            if (!profileId.equals(expectedAppearance.profileId())) {
                throw new IllegalArgumentException(
                        "Resolved profile does not belong to the expected account");
            }
        }
    }
}
