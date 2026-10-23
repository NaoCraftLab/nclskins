package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderChannel;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.runtime.AccountReconciliationPort.DurableAppearance;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ProviderRefreshResult(ProviderRefreshUseCases.Scope scope, Optional<DurableAppearance> appearance,
        Map<BuiltinProvider, Outcome> outcomes, boolean superseded) {
    public enum Status { CONFIRMED, UNKNOWN, FAILED, RATE_LIMITED, SUPERSEDED }

    public record Outcome(Status status, ProviderObservation<?> observation) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            if (status == Status.CONFIRMED && (observation == null || !observation.known()))
                throw new IllegalArgumentException("Confirmed outcome needs an observation");
            if (status != Status.CONFIRMED && observation != null)
                throw new IllegalArgumentException("Unconfirmed outcome cannot carry an observation");
        }

        public static Outcome unconfirmed(Status status) { return new Outcome(status, null); }
    }

    public ProviderRefreshResult {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(appearance, "appearance");
        outcomes = Map.copyOf(outcomes);
        if (!scope.providers().containsAll(outcomes.keySet())) throw new IllegalArgumentException("Outcome outside scope");
    }

    public static ProviderRefreshResult terminal(ProviderRefreshUseCases.Scope scope, Status status) {
        var outcomes = new java.util.EnumMap<BuiltinProvider, Outcome>(BuiltinProvider.class);
        scope.providers().forEach(provider -> outcomes.put(provider, Outcome.unconfirmed(status)));
        return new ProviderRefreshResult(scope, Optional.empty(), outcomes, status == Status.SUPERSEDED);
    }

    public Optional<DurableAppearance> merge(AppearanceProviders current, Optional<AppliedAppearance> previous) {
        if (superseded || appearance.isEmpty() || !scope.configurationMatches(current)) return Optional.empty();
        var saved = appearance.orElseThrow();
        var merged = scope.request().component() == AppearanceProviders.Component.SKIN
                ? new AppearanceProviders(mergeChannel(current.skin(), saved.providers().skin()), current.cape())
                : new AppearanceProviders(current.skin(), mergeChannel(current.cape(), saved.providers().cape()));
        var skin = merged.skin().resolve().map(ProviderChannel.Resolved::value);
        var cape = merged.cape().resolve().map(ProviderChannel.Resolved::value);
        var oldSkin = current.skin().resolve();
        var oldCape = current.cape().resolve();
        boolean sameSkin = merged.skin().resolve().equals(oldSkin);
        boolean sameCape = merged.cape().resolve().equals(oldCape);
        var local = previous.filter(ignored -> sameSkin && sameCape).or(() -> Optional.of(new AppliedAppearance(
                saved.accountId(), sameSkin && previous.isPresent() ? previous.orElseThrow().skinTexture() : Optional.empty(),
                sameSkin && previous.isPresent() ? previous.orElseThrow().localSkinSha256() : skin.map(value -> value.sha256()),
                sameSkin && previous.isPresent() ? previous.orElseThrow().skinVariant() : skin.map(value -> value.variant()),
                sameCape && previous.isPresent() ? previous.orElseThrow().capeTexture() : Optional.empty(),
                sameCape && previous.isPresent() ? previous.orElseThrow().localCapeCacheKey() : cape.flatMap(value -> value.optionalTextureCacheKey()),
                sameCape && previous.isPresent() ? previous.orElseThrow().capeHasElytra()
                        : cape.map(value -> !Boolean.FALSE.equals(value.hasElytra())).orElse(true))));
        return Optional.of(new DurableAppearance(saved.accountId(), saved.intentRevision(), saved.syncStatus(),
                saved.activePresetId(), local, saved.outerLayerVisibility(), merged));
    }

    private <T> ProviderChannel<T> mergeChannel(ProviderChannel<T> current, ProviderChannel<T> saved) {
        var baseline = scope.request().component() == AppearanceProviders.Component.SKIN ? scope.before().skin() : scope.before().cape();
        Map<BuiltinProvider, ProviderObservation<T>> observations = new java.util.EnumMap<>(BuiltinProvider.class);
        for (BuiltinProvider source : BuiltinProvider.values()) observations.put(source, current.observation(source));
        for (BuiltinProvider source : scope.providers()) {
            var behavior = ProviderRefreshBehavior.of(source, scope.request().component());
            var outcome = outcomes.get(source);
            boolean adopt = behavior.observation() == ProviderRefreshBehavior.Observation.LOCAL
                    || behavior.observation() == ProviderRefreshBehavior.Observation.SESSION
                        && outcome != null && outcome.status() == Status.CONFIRMED;
            if (adopt && current.observation(source).equals(baseline.observation(source)))
                observations.put(source, saved.observation(source));
        }
        return new ProviderChannel<>(current.order(), observations.get(BuiltinProvider.OFFLINE),
                observations.get(BuiltinProvider.MINECRAFT), current.configurationRevision(),
                current.intentRevision(), current.desired(), current.minecraftDelivery(), current.offlineDesired(),
                observations.get(BuiltinProvider.OPTIFINE), observations.get(BuiltinProvider.SKINMC),
                observations.get(BuiltinProvider.SNEAKY), observations.get(BuiltinProvider.MINECRAFTCAPES),
                current.cpmConfiguration(), current.serializedOrder());
    }
}
