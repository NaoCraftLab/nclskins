package com.naocraftlab.skins.compat.environment;

import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinExtensionEnvironmentSource;
import net.fabricmc.loader.api.FabricLoader;


public final class FabricSkinExtensionEnvironmentSource
        implements SkinExtensionEnvironmentSource {
    private final SkinExtensionEnvironmentSupport delegate;

    public FabricSkinExtensionEnvironmentSource(SkinCatalogSource resources) {
        FabricLoader loader = FabricLoader.getInstance();
        delegate = new SkinExtensionEnvironmentSupport(
                resources,
                state(loader, "ears"),
                state(loader, "entity_model_features"),
                state(loader, "entity_texture_features"));
    }

    @Override
    public Snapshot snapshot() {
        return delegate.snapshot();
    }

    private static State state(FabricLoader loader, String modId) {
        try {
            return loader.isModLoaded(modId) ? State.ACTIVE : State.INACTIVE;
        } catch (RuntimeException unavailable) {
            return State.UNKNOWN;
        }
    }
}
