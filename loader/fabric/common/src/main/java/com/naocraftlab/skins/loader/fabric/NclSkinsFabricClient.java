package com.naocraftlab.skins.loader.fabric;

import net.fabricmc.api.ClientModInitializer;


public final class NclSkinsFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricBuiltInPackRegistrar.registerMojangCollections();
        FabricClientBridge.install();
    }
}
