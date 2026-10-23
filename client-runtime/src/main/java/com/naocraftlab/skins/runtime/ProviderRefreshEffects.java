package com.naocraftlab.skins.runtime;

public interface ProviderRefreshEffects {
    boolean providerRefreshCurrent(ProviderRefreshUseCases.Scope scope);
    ProviderRefreshResult refreshProviderSources(ProviderRefreshUseCases.Scope scope) throws Exception;
}
