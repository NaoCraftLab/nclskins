package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;


final class NeoForgeConfigScreenRegistrar {
    private NeoForgeConfigScreenRegistrar() {}

    static void register() {
        IConfigScreenFactory factory = (container, parent) ->
                MinecraftConfigurationBridge.createScreen(parent);
        ModList.get()
                .getModContainerById(NclSkinsNeoForgeMod.MOD_ID)
                .orElseThrow(() -> new IllegalStateException("NCL Skins mod container is missing"))
                .registerExtensionPoint(IConfigScreenFactory.class, factory);
    }
}
