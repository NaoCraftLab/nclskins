package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.AppearanceProviders.Component;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public record ProviderRefreshBehavior(Observation observation, Preparation preparation, Cooldown cooldown,
        boolean confirmedChangeSignal) {
    public enum Observation { LOCAL, SESSION, EXTERNAL, DERIVED }
    public enum Preparation { LOCAL_ASSET, REMOTE_REFERENCE, EXISTING_DERIVED, OWNED }
    public enum Cooldown { NONE, SESSION, SOURCE }

    private static final Map<BuiltinProvider, ProviderRefreshBehavior> CAPES = Map.of(
            BuiltinProvider.CPM, new ProviderRefreshBehavior(Observation.DERIVED, Preparation.EXISTING_DERIVED, Cooldown.NONE, false),
            BuiltinProvider.OFFLINE, new ProviderRefreshBehavior(Observation.LOCAL, Preparation.LOCAL_ASSET, Cooldown.NONE, false),
            BuiltinProvider.MINECRAFT, new ProviderRefreshBehavior(Observation.SESSION, Preparation.REMOTE_REFERENCE, Cooldown.SESSION, true),
            BuiltinProvider.OPTIFINE, new ProviderRefreshBehavior(Observation.EXTERNAL, Preparation.OWNED, Cooldown.SOURCE, true),
            BuiltinProvider.SKINMC, new ProviderRefreshBehavior(Observation.EXTERNAL, Preparation.OWNED, Cooldown.SOURCE, true),
            BuiltinProvider.MINECRAFTCAPES, new ProviderRefreshBehavior(Observation.EXTERNAL, Preparation.OWNED, Cooldown.SOURCE, true),
            BuiltinProvider.SNEAKY, new ProviderRefreshBehavior(Observation.DERIVED, Preparation.EXISTING_DERIVED, Cooldown.NONE, false));

    public ProviderRefreshBehavior {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(cooldown, "cooldown");
        if ((observation == Observation.LOCAL || observation == Observation.DERIVED)
                && (cooldown != Cooldown.NONE || confirmedChangeSignal))
            throw new IllegalArgumentException("Local observation cannot declare read cooldown or signal");
        boolean compatible = switch (observation) {
            case LOCAL -> preparation == Preparation.LOCAL_ASSET || preparation == Preparation.OWNED;
            case SESSION -> preparation == Preparation.REMOTE_REFERENCE || preparation == Preparation.OWNED;
            case EXTERNAL -> preparation == Preparation.OWNED;
            case DERIVED -> preparation == Preparation.EXISTING_DERIVED;
        };
        if (!compatible
                || cooldown == Cooldown.SESSION && observation != Observation.SESSION
                || cooldown == Cooldown.SOURCE && observation != Observation.EXTERNAL)
            throw new IllegalArgumentException("Incompatible provider behavior");
    }

    public static ProviderRefreshBehavior of(BuiltinProvider provider, Component component) {
        if (!(component == Component.SKIN ? provider.supportsSkin() : provider.supportsCape()))
            throw new IllegalArgumentException("Unsupported provider component");
        ProviderRefreshBehavior value = Objects.requireNonNull(CAPES.get(provider), "Missing Refresh behavior");
        return component == Component.SKIN && value.preparation == Preparation.REMOTE_REFERENCE
                ? new ProviderRefreshBehavior(value.observation, Preparation.OWNED, value.cooldown, value.confirmedChangeSignal) : value;
    }

    public boolean sharedPreparation() {
        return preparation == Preparation.LOCAL_ASSET || preparation == Preparation.REMOTE_REFERENCE;
    }

    public static <P> List<P> resolve(List<P> enabled, Optional<P> selected,
            Function<P, ProviderRefreshBehavior> behavior) {
        List<P> sources = selected.map(source -> enabled.contains(source) ? List.of(source) : List.<P>of()).orElse(enabled);
        sources.forEach(source -> Objects.requireNonNull(behavior.apply(source), "Missing Refresh behavior"));
        return List.copyOf(sources);
    }

    public static <P> boolean blocked(List<P> sources, Map<P, Duration> cooldowns) {
        return !sources.isEmpty() && sources.stream().allMatch(source -> {
            Duration value = cooldowns.get(source);
            return value != null && !value.isNegative() && !value.isZero();
        });
    }

    public static <P> Map<P, Duration> cooldowns(List<P> providers, Function<P, ProviderRefreshBehavior> behavior,
            Optional<Duration> session, Map<P, Duration> sources) {
        Map<P, Duration> facts = new java.util.HashMap<>();
        for (P source : providers) {
            Duration remaining = switch (behavior.apply(source).cooldown()) {
                case NONE -> null;
                case SESSION -> session.orElse(null);
                case SOURCE -> sources.get(source);
            };
            if (remaining != null) facts.put(source, remaining);
        }
        return Map.copyOf(facts);
    }
}
