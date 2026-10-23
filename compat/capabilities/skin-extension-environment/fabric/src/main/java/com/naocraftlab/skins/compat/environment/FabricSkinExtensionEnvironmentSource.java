package com.naocraftlab.skins.compat.environment;

import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinExtensionEnvironmentSource;
import net.fabricmc.loader.api.FabricLoader;


public final class FabricSkinExtensionEnvironmentSource
        implements SkinExtensionEnvironmentSource {
    private final SkinExtensionEnvironmentSupport delegate;
    private final State cpm;

    public FabricSkinExtensionEnvironmentSource(SkinCatalogSource resources) {
        FabricLoader loader = FabricLoader.getInstance();
        cpm = state(loader, "cpm");
        if (cpm == State.ACTIVE) com.naocraftlab.skins.compat.cpm.CpmIntegration.initialize(resources);
        delegate = new SkinExtensionEnvironmentSupport(
                resources,
                state(loader, "ears"),
                state(loader, "entity_model_features"),
                state(loader, "entity_texture_features"),
                cpm == State.ACTIVE ? com.naocraftlab.skins.compat.cpm.CpmIntegration::modelLoadingState : () -> cpm);
    }

    @Override public java.util.Optional<com.naocraftlab.skins.client.CpmModelFileConversion> modelFileConversion() {
        try {
            return cpm == State.ACTIVE ? com.naocraftlab.skins.compat.cpm.CpmIntegration.modelFileConversion() : java.util.Optional.empty();
        } catch (RuntimeException | LinkageError unavailable) { return java.util.Optional.empty(); }
    }

    @Override public Subscription subscribe(Runnable changed) {
        return cpm == State.ACTIVE ? com.naocraftlab.skins.compat.cpm.CpmIntegration.subscribe(changed) : () -> {};
    }

    @Override
    public Snapshot snapshot() {
        Snapshot snapshot = delegate.snapshot();
        return new Snapshot(snapshot.generation(), snapshot.consumers(), cpm == State.ACTIVE);
    }

    private static State state(FabricLoader loader, String modId) {
        try {
            return loader.isModLoaded(modId) ? State.ACTIVE : State.INACTIVE;
        } catch (RuntimeException unavailable) {
            return State.UNKNOWN;
        }
    }
}
