package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;


final class NeoForgeClientBridge {
    private static final MinecraftClientHookAdapter CLIENT_HOOKS =
            MinecraftClientHookAdapter.instance();

    private NeoForgeClientBridge() {}

    static void install() {
        NeoForgeClientLifecycle.install(CLIENT_HOOKS);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientBridge::afterScreenInit);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientBridge::afterScreenRender);
    }

    private static void afterScreenInit(ScreenEvent.Init.Post event) {
        CLIENT_HOOKS.afterScreenInit(
                Minecraft.getInstance(),
                event.getScreen(),
                event.getScreen().width,
                event.getScreen().height,
                event::addListener);
    }

    private static void afterScreenRender(ScreenEvent.Render.Post event) {
        CLIENT_HOOKS.afterScreenFrame(
                event.getScreen(),
                new MinecraftClientHookAdapter.Frame(
                        event.getGuiGraphics(),
                        event.getMouseX(),
                        event.getMouseY()));
    }
}
