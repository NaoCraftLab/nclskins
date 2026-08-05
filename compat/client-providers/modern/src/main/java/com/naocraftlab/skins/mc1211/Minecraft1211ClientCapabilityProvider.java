package com.naocraftlab.skins.mc1211;

import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.compat.client.MinecraftClientExecutor;
import com.naocraftlab.skins.compat.client.MinecraftFilePicker;
import com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource;
import com.naocraftlab.skins.compat.client.MinecraftServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.compat.gui.immediate.MinecraftSignedTextureVerifier;
import com.naocraftlab.skins.runtime.ClientCapabilityProvider;
import com.naocraftlab.skins.runtime.ClientCapabilitySet;


public final class Minecraft1211ClientCapabilityProvider implements ClientCapabilityProvider {
    @Override
    public Provision provision() {
        Minecraft1211AppearanceCapability appearance =
                new Minecraft1211AppearanceCapability();
        CurrentPlayerAppearanceSource currentAppearance =
                new Minecraft1211CurrentPlayerAppearanceSource(appearance::installedAppearance);
        return new Provision(
                new ClientCapabilitySet(
                        new MinecraftGameSessionTokenSource(),
                        new Minecraft1211BundledSkinSource(),
                        currentAppearance,
                        new MinecraftClientExecutor(),
                        new MinecraftFilePicker(),
                        new MinecraftSignedTextureVerifier(),
                        appearance,
                        new Minecraft1211OuterLayerVisibilityController(),
                        new MinecraftServerAppearanceRefreshNotifier()),
                appearance::maintain,
                appearance::close);
    }
}
