package com.naocraftlab.skins.compat.client.identifier;

import com.naocraftlab.skins.compat.client.MinecraftClientExecutor;
import com.naocraftlab.skins.compat.client.MinecraftFilePicker;
import com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource;
import com.naocraftlab.skins.compat.client.MinecraftServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.generated.TargetClientBindings;
import com.naocraftlab.skins.runtime.ClientCapabilityProvider;
import com.naocraftlab.skins.runtime.ClientCapabilitySet;
import com.naocraftlab.skins.runtime.NativeResourceMaintenance;


public final class IdentifierClientCapabilityProvider implements ClientCapabilityProvider {
    @Override
    public Provision provision() {
        IdentifierAppearanceSink appearance = new IdentifierAppearanceSink();
        MinecraftClientExecutor clientExecutor = new MinecraftClientExecutor();
        IdentifierBundledSkinSource bundledSkins = new IdentifierBundledSkinSource();
        NativeResourceMaintenance maintenance = new NativeResourceMaintenance(
                appearance::hasActiveOverride, appearance::maintain);
        appearance.useMaintenanceDirty(maintenance::markDirty);
        return new Provision(
                new ClientCapabilitySet(
                        new MinecraftGameSessionTokenSource(),
                        bundledSkins,
                        new IdentifierCurrentPlayerAppearanceSource(appearance::installedSkin),
                        clientExecutor,
                        new MinecraftFilePicker(clientExecutor),
                        new MinecraftSessionSignedTextureVerifier(),
                        appearance,
                        new IdentifierOuterLayerVisibilityController(),
                        new MinecraftServerAppearanceRefreshNotifier(),
                        TargetClientBindings.skinExtensionEnvironment(bundledSkins)),
                maintenance,
                appearance::close);
    }
}
