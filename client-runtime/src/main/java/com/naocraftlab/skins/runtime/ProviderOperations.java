package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;

import java.util.UUID;

import com.naocraftlab.skins.runtime.AccountReconciliationPort.DurableAppearance;

public interface ProviderOperations {
    AppearanceProviders loadProviders() throws Exception;

    DurableAppearance reloadProviders() throws Exception;

    DurableAppearance enableProvider(AppearanceProviders.Component component, BuiltinProvider provider)
            throws Exception;

    DurableAppearance disableProvider(AppearanceProviders.Component component, BuiltinProvider provider)
            throws Exception;

    DurableAppearance moveProvider(
            AppearanceProviders.Component component, BuiltinProvider provider, int direction) throws Exception;

    DurableAppearance enableProvider(UUID accountId, AppearanceProviders.Component component, BuiltinProvider provider) throws Exception;

    DurableAppearance disableProvider(UUID accountId, AppearanceProviders.Component component, BuiltinProvider provider) throws Exception;

    DurableAppearance moveProvider(UUID accountId, AppearanceProviders.Component component, BuiltinProvider provider, int direction) throws Exception;
    DurableAppearance enableProvider(UUID accountId, AppearanceProviders.Component component,
            BuiltinProvider provider, long configurationRevision) throws Exception;

    DurableAppearance disableProvider(UUID accountId, AppearanceProviders.Component component,
            BuiltinProvider provider, long configurationRevision) throws Exception;

    DurableAppearance moveProvider(UUID accountId, AppearanceProviders.Component component,
            BuiltinProvider provider, int direction, long configurationRevision) throws Exception;
}
