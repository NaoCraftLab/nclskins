package com.naocraftlab.skins.client;

import com.naocraftlab.skins.client.SignedProfileResolver.ResolvedProfile;

import java.util.Objects;


@FunctionalInterface
public interface PlayerAppearanceSink<P> {
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

    enum ApplyResult {
        UPDATED,
        DEFERRED
    }
}
