package com.naocraftlab.skins.compat.mc262;

import com.naocraftlab.skins.compat.client.MinecraftClientExecutor;
import com.naocraftlab.skins.compat.client.MinecraftFilePicker;
import com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource;
import com.naocraftlab.skins.compat.client.MinecraftServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.runtime.ClientCapabilityProvider;
import com.naocraftlab.skins.runtime.ClientCapabilitySet;


public final class Minecraft262ClientCapabilityProvider implements ClientCapabilityProvider {
    @Override
    public Provision provision() {
        Minecraft262AppearanceSink appearance = new Minecraft262AppearanceSink();
        return new Provision(
                new ClientCapabilitySet(
                        new MinecraftGameSessionTokenSource(),
                        new Minecraft262BundledSkinSource(),
                        new Minecraft262CurrentPlayerAppearanceSource(appearance::installedSkin),
                        new MinecraftClientExecutor(),
                        new MinecraftFilePicker(),
                        new Minecraft262SignedTextureVerifier(),
                        appearance,
                        new Minecraft262OuterLayerVisibilityController(),
                        new MinecraftServerAppearanceRefreshNotifier()),
                appearance::maintain,
                appearance::close);
    }
}
