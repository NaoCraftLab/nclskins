package com.naocraftlab.skins.buildlogic.boot;

public final class LegacyClientLaunchHandler extends net.minecraftforge.fml.loading.targets.ForgeClientUserdevLaunchHandler {
    @Override public String name() { return "nclskinsforgeclientdevverification"; }
    @Override protected void clientService(String[] args, ModuleLayer gameLayer) throws Throwable {
        LegacyGameService.run(gameLayer);
    }
}
