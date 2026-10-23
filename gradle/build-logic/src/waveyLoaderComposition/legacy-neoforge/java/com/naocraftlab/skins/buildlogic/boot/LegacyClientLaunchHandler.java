package com.naocraftlab.skins.buildlogic.boot;

public final class LegacyClientLaunchHandler extends net.neoforged.fml.loading.targets.NeoForgeClientDevLaunchHandler {
    @Override public String name() { return "nclskinsneoforgeclientdevverification"; }
    @Override protected void clientService(String[] args, ModuleLayer gameLayer) throws Throwable {
        LegacyGameService.run(gameLayer);
    }
}
