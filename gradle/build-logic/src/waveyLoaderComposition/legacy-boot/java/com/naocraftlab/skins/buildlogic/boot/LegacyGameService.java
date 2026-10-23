package com.naocraftlab.skins.buildlogic.boot;

import cpw.mods.modlauncher.api.ServiceRunner;
import java.util.ServiceLoader;

public final class LegacyGameService {
    public static void run(ModuleLayer gameLayer) throws Throwable {
        var providers = ServiceLoader.load(gameLayer, ServiceRunner.class).stream()
                .filter(p -> p.type().getName().equals("com.naocraftlab.skins.buildlogic.probe.wavey.LegacyCapeService")).toList();
        if (providers.size() != 1) throw new AssertionError("GAME_SERVICE_CARDINALITY");
        var provider = providers.get(0);
        if (provider.type().getModule().getLayer() != gameLayer
                || provider.type().getClassLoader() != Thread.currentThread().getContextClassLoader())
            throw new AssertionError("GAME_LAYER_TCCL_IDENTITY");
        provider.get().run();
        System.out.println("CANONICAL_CLIENT_CLOSED_PASS");
    }
}
