package com.naocraftlab.skins.core.provider;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum BuiltinProvider {
    OFFLINE(true, true, capabilities(ProviderCapability.WRITE), capabilities(ProviderCapability.WRITE)),
    MINECRAFT(true, true,
            capabilities(ProviderCapability.WRITE, ProviderCapability.DISTRIBUTION),
            capabilities(ProviderCapability.LIMITED_WRITE, ProviderCapability.DISTRIBUTION)),
    OPTIFINE(false, true, Set.of(), capabilities(ProviderCapability.DISTRIBUTION)),
    SKINMC(false, true, Set.of(), capabilities(ProviderCapability.DISTRIBUTION)),
    SNEAKY(false, true, Set.of(), capabilities(ProviderCapability.DISTRIBUTION));

    private final boolean skin;
    private final boolean cape;
    private final Set<ProviderCapability> skinCapabilities;
    private final Set<ProviderCapability> capeCapabilities;

    BuiltinProvider(boolean skin, boolean cape, Set<ProviderCapability> skinCapabilities,
            Set<ProviderCapability> capeCapabilities) {
        this.skin = skin;
        this.cape = cape;
        this.skinCapabilities = skinCapabilities;
        this.capeCapabilities = capeCapabilities;
    }

    public boolean supportsSkin() {
        return skin;
    }

    public boolean supportsCape() {
        return cape;
    }

    public Set<ProviderCapability> capabilities(AppearanceProviders.Component component) {
        return switch (component) {
            case SKIN -> skinCapabilities;
            case CAPE -> capeCapabilities;
        };
    }

    public boolean canWrite(AppearanceProviders.Component component) {
        Set<ProviderCapability> capabilities = capabilities(component);
        return capabilities.contains(ProviderCapability.WRITE)
                || capabilities.contains(ProviderCapability.LIMITED_WRITE);
    }

    private static Set<ProviderCapability> capabilities(ProviderCapability first,
            ProviderCapability... rest) {
        EnumSet<ProviderCapability> values = EnumSet.of(first, rest);
        return Collections.unmodifiableSet(values);
    }
}
