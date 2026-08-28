package com.naocraftlab.skins.compat.client.identifier;

import com.naocraftlab.skins.compat.client.MinecraftClientExecutor;
import com.naocraftlab.skins.compat.client.MinecraftFilePicker;
import com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource;
import com.naocraftlab.skins.compat.client.MinecraftServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.generated.TargetClientBindings;
import com.naocraftlab.skins.runtime.ClientCapabilityProvider;
import com.naocraftlab.skins.runtime.ClientCapabilitySet;


public final class IdentifierClientCapabilityProvider implements ClientCapabilityProvider {
    @Override
    public Provision provision() {
        IdentifierAppearanceSink appearance = new IdentifierAppearanceSink();
        MinecraftClientExecutor clientExecutor = new MinecraftClientExecutor();
        IdentifierBundledSkinSource bundledSkins = new IdentifierBundledSkinSource();
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
                appearance::maintain,
                appearance::close);
    }
}
