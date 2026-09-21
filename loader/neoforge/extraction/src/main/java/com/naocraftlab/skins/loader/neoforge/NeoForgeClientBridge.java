package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.client.ClientLifecycleGate;
import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.loading.FMLPaths;


final class NeoForgeClientBridge {
    private static final MinecraftClientHookAdapter CLIENT_HOOKS =
            MinecraftClientHookAdapter.instance();
    private static final ClientLifecycleGate<MinecraftClientHookAdapter> LIFECYCLE =
            new ClientLifecycleGate<>(
                    MinecraftClientHookAdapter::resourcesReloaded,
                    MinecraftClientHookAdapter::close);

    private NeoForgeClientBridge() {}

    static void install() {
        NeoForgeClientLifecycle.install(LIFECYCLE, CLIENT_HOOKS, FMLPaths.CONFIGDIR.get());
        NeoForge.EVENT_BUS.addListener(NeoForgeClientBridge::afterScreenInit);
    }

    static void resourcesReloaded() {
        LIFECYCLE.resourcesReloaded();
    }

    private static void afterScreenInit(ScreenEvent.Init.Post event) {
        LIFECYCLE.dispatch(current -> current.afterScreenInit(
                Minecraft.getInstance(), event.getScreen(), event.getScreen().width,
                event.getScreen().height, event::addListener));
    }
}
