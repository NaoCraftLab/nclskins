package com.naocraftlab.skins.loader.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;


@Mod(NclSkinsNeoForgeMod.MOD_ID)
public final class NclSkinsNeoForgeMod {
    public static final String MOD_ID = "nclskins";

    public NclSkinsNeoForgeMod(IEventBus modBus) {
        modBus.addListener(NeoForgeServerBridge::registerPayloads);
    }
}
