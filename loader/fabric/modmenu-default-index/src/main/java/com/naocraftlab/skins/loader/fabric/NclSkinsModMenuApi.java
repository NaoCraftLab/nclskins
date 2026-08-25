package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.compat.config.MinecraftConfigurationBridge;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;


public final class NclSkinsModMenuApi implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MinecraftConfigurationBridge::createScreen;
    }
}
