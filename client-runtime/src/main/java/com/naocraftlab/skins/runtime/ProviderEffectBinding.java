package com.naocraftlab.skins.runtime;

import java.util.Objects;

record ProviderEffectBinding<T>(ProviderRefreshBehavior behavior, T effect) {
    ProviderEffectBinding {
        Objects.requireNonNull(behavior, "behavior");
        Objects.requireNonNull(effect, "Missing provider effect");
    }

    T require(ProviderRefreshBehavior.Observation observation) {
        if (behavior.observation() != observation) throw new IllegalArgumentException("Incompatible provider effect");
        return effect;
    }
}
