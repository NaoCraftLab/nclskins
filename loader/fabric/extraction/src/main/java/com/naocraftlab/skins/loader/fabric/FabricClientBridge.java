package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.client.ClientLifecycleGate;
import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;


final class FabricClientBridge {
    private static final MinecraftClientHookAdapter CLIENT_HOOKS =
            MinecraftClientHookAdapter.instance();
    private static final ClientLifecycleGate<MinecraftClientHookAdapter> LIFECYCLE =
            new ClientLifecycleGate<>(
                    MinecraftClientHookAdapter::resourcesReloaded,
                    MinecraftClientHookAdapter::close);

    private FabricClientBridge() {}

    static void install() {
        FabricPipRendererRegistration.register();
        FabricClientLifecycle.install(
                LIFECYCLE, CLIENT_HOOKS, FabricLoader.getInstance().getConfigDir());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                LIFECYCLE.dispatch(current -> current.afterScreenInit(
                        client, screen, width, height, Screens.getWidgets(screen)::add)));
    }

    static void resourcesReloaded() {
        LIFECYCLE.resourcesReloaded();
    }
}
