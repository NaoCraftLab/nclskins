package com.naocraftlab.skins.compat.environment;

import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinExtensionEnvironmentSource;
import net.neoforged.fml.ModList;


public final class NeoForgeSkinExtensionEnvironmentSource
        implements SkinExtensionEnvironmentSource {
    private final SkinExtensionEnvironmentSupport delegate;

    public NeoForgeSkinExtensionEnvironmentSource(SkinCatalogSource resources) {
        ModList mods = ModList.get();
        delegate = new SkinExtensionEnvironmentSupport(
                resources,
                state(mods, "ears"),
                state(mods, "entity_model_features"),
                state(mods, "entity_texture_features"));
    }

    @Override
    public Snapshot snapshot() {
        return delegate.snapshot();
    }

    private static State state(ModList mods, String modId) {
        try {
            return mods.isLoaded(modId) ? State.ACTIVE : State.INACTIVE;
        } catch (RuntimeException unavailable) {
            return State.UNKNOWN;
        }
    }
}
