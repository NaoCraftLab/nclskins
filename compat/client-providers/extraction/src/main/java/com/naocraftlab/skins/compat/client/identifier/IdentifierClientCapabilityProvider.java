package com.naocraftlab.skins.compat.client.identifier;

import com.naocraftlab.skins.compat.client.MinecraftClientExecutor;
import com.naocraftlab.skins.compat.client.MinecraftFilePicker;
import com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource;
import com.naocraftlab.skins.compat.client.MinecraftServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.runtime.ClientCapabilityProvider;
import com.naocraftlab.skins.runtime.ClientCapabilitySet;


public final class IdentifierClientCapabilityProvider implements ClientCapabilityProvider {
    @Override
    public Provision provision() {
        IdentifierAppearanceSink appearance = new IdentifierAppearanceSink();
        MinecraftClientExecutor clientExecutor = new MinecraftClientExecutor();
        return new Provision(
                new ClientCapabilitySet(
                        new MinecraftGameSessionTokenSource(),
                        new IdentifierBundledSkinSource(),
                        new IdentifierCurrentPlayerAppearanceSource(appearance::installedSkin),
                        clientExecutor,
                        new MinecraftFilePicker(clientExecutor),
                        new MinecraftSessionSignedTextureVerifier(),
                        appearance,
                        new IdentifierOuterLayerVisibilityController(),
                        new MinecraftServerAppearanceRefreshNotifier()),
                appearance::maintain,
                appearance::close);
    }
}
