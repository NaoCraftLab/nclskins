package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.client.ClientLifecycleGate;
import com.naocraftlab.skins.compat.loader.MinecraftClientHookAdapter;
import com.naocraftlab.skins.compat.client.gui.CapeGuiFoilMaterial;
import com.naocraftlab.skins.compat.client.cape.CapeTranslucentFoilMaterial;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
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
        CoreShaderRegistrationCallback.EVENT.register(context -> context.register(
                CapeGuiFoilMaterial.SHADER, DefaultVertexFormat.POSITION_TEX_COLOR,
                CapeGuiFoilMaterial::shaderLoaded));
        CoreShaderRegistrationCallback.EVENT.register(context -> context.register(
                CapeTranslucentFoilMaterial.SHADER, DefaultVertexFormat.NEW_ENTITY,
                CapeTranslucentFoilMaterial::shaderLoaded));
        FabricClientLifecycle.install(
                LIFECYCLE, CLIENT_HOOKS, FabricLoader.getInstance().getConfigDir());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            LIFECYCLE.dispatch(current -> current.afterScreenInit(
                    client, screen, width, height, Screens.getButtons(screen)::add));
            ScreenEvents.remove(screen).register(
                    removed -> LIFECYCLE.dispatch(current -> current.screenRemoved(removed)));
        });
    }

    static void resourcesReloaded() {
        LIFECYCLE.resourcesReloaded();
    }
}
