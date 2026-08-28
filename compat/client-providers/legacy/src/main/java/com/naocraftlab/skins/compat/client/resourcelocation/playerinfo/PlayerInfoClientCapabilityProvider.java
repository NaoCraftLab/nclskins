package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.compat.client.MinecraftClientExecutor;
import com.naocraftlab.skins.compat.client.MinecraftFilePicker;
import com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource;
import com.naocraftlab.skins.compat.client.MinecraftServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.compat.gui.immediate.MinecraftSignedTextureVerifier;
import com.naocraftlab.skins.generated.TargetClientBindings;
import com.naocraftlab.skins.runtime.ClientCapabilityProvider;
import com.naocraftlab.skins.runtime.ClientCapabilitySet;


public final class PlayerInfoClientCapabilityProvider implements ClientCapabilityProvider {
    @Override
    public Provision provision() {
        PlayerInfoAppearanceCapability appearance =
                new PlayerInfoAppearanceCapability();
        CurrentPlayerAppearanceSource currentAppearance =
                new ResourceLocationCurrentPlayerAppearanceSource(appearance::installedAppearance);
        MinecraftClientExecutor clientExecutor = new MinecraftClientExecutor();
        ResourceStackBundledSkinSource bundledSkins = new ResourceStackBundledSkinSource();
        return new Provision(
                new ClientCapabilitySet(
                        new MinecraftGameSessionTokenSource(),
                        bundledSkins,
                        currentAppearance,
                        clientExecutor,
                        new MinecraftFilePicker(clientExecutor),
                        new MinecraftSignedTextureVerifier(),
                        appearance,
                        new PlayerInfoOuterLayerVisibilityController(),
                        new MinecraftServerAppearanceRefreshNotifier(),
                        TargetClientBindings.skinExtensionEnvironment(bundledSkins)),
                appearance::maintain,
                appearance::close);
    }
}
