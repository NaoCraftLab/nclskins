package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;


final class FabricClientBridge {
    private static final MinecraftClientHookAdapter CLIENT_HOOKS =
            MinecraftClientHookAdapter.instance();

    private FabricClientBridge() {}

    static void install() {
        FabricClientLifecycle.install(CLIENT_HOOKS);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            CLIENT_HOOKS.afterScreenInit(
                    client, screen, width, height, Screens.getButtons(screen)::add);


            ScreenEvents.afterRender(screen).register((rendered, graphics, mouseX, mouseY, ignoredPartialTick) ->
                    CLIENT_HOOKS.afterScreenFrame(
                            rendered,
                            new MinecraftClientHookAdapter.Frame(
                                    graphics, mouseX, mouseY)));
            ScreenEvents.remove(screen).register(CLIENT_HOOKS::screenRemoved);
        });
    }
}
