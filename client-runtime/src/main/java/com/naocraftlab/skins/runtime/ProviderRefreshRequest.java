package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ProviderRefreshRequest(AppearanceProviders.Component component, Optional<BuiltinProvider> provider) {
    public ProviderRefreshRequest {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(provider, "provider");
    }

    public static ProviderRefreshRequest all(AppearanceProviders.Component component) {
        return new ProviderRefreshRequest(component, Optional.empty());
    }

    public static ProviderRefreshRequest one(AppearanceProviders.Component component, BuiltinProvider provider) {
        return new ProviderRefreshRequest(component, Optional.of(provider));
    }

    public List<BuiltinProvider> resolve(AppearanceProviders providers) {
        var order = component == AppearanceProviders.Component.SKIN ? providers.skin().order() : providers.cape().order();
        return ProviderRefreshBehavior.resolve(order, provider, source -> ProviderRefreshBehavior.of(source, component));
    }

    public boolean blockedByRateLimit(AppearanceProviders providers, Optional<Duration> minecraft,
            Map<BuiltinProvider, Duration> capes) {
        return ProviderRefreshBehavior.blocked(resolve(providers), cooldownFacts(component, minecraft, capes));
    }

    public static Map<BuiltinProvider, Duration> cooldownFacts(AppearanceProviders.Component component,
            Optional<Duration> session, Map<BuiltinProvider, Duration> sources) {
        var supported = java.util.Arrays.stream(BuiltinProvider.values()).filter(provider ->
                component == AppearanceProviders.Component.SKIN ? provider.supportsSkin() : provider.supportsCape()).toList();
        return ProviderRefreshBehavior.cooldowns(supported, source -> ProviderRefreshBehavior.of(source, component), session, sources);
    }
}
