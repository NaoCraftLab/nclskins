package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.AppearanceProviders;

import java.util.UUID;

record RefreshComparison(UUID accountId, String canonicalName,
        AppearanceProviders.Component component, AppearanceProviders providers) {}
