package com.naocraftlab.skins.loader.fabric;

import net.fabricmc.api.ModInitializer;


public final class NclSkinsFabricServer implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricServerBridge.install();
    }
}
