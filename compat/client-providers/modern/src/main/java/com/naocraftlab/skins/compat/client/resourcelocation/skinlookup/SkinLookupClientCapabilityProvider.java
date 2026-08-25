package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.compat.client.MinecraftClientExecutor;
import com.naocraftlab.skins.compat.client.MinecraftFilePicker;
import com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource;
import com.naocraftlab.skins.compat.client.MinecraftServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.compat.gui.immediate.MinecraftSignedTextureVerifier;
import com.naocraftlab.skins.runtime.ClientCapabilityProvider;
import com.naocraftlab.skins.runtime.ClientCapabilitySet;


public final class SkinLookupClientCapabilityProvider implements ClientCapabilityProvider {
    @Override
    public Provision provision() {
        SkinLookupAppearanceCapability appearance =
                new SkinLookupAppearanceCapability();
        CurrentPlayerAppearanceSource currentAppearance =
                new PlayerSkinCurrentAppearanceSource(appearance::installedAppearance);
        MinecraftClientExecutor clientExecutor = new MinecraftClientExecutor();
        return new Provision(
                new ClientCapabilitySet(
                        new MinecraftGameSessionTokenSource(),
                        new VanillaPackBundledSkinSource(),
                        currentAppearance,
                        clientExecutor,
                        new MinecraftFilePicker(clientExecutor),
                        new MinecraftSignedTextureVerifier(),
                        appearance,
                        new SkinLookupOuterLayerVisibilityController(),
                        new MinecraftServerAppearanceRefreshNotifier()),
                appearance::maintain,
                appearance::close);
    }
}
