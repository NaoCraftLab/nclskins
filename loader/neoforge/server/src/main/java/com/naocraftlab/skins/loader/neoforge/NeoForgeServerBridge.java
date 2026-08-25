package com.naocraftlab.skins.loader.neoforge;

import com.naocraftlab.skins.compat.server.MinecraftServerLifecycle;
import com.naocraftlab.skins.compat.server.MinecraftAppearanceRefreshNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;


@EventBusSubscriber(modid = NclSkinsNeoForgeMod.MOD_ID)
final class NeoForgeServerBridge {
    private NeoForgeServerBridge() {}

    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        MinecraftAppearanceRefreshNetwork.registerPayloads(event);
    }

    @SubscribeEvent
    static void serverStarting(ServerStartingEvent event) {
        MinecraftServerLifecycle.started(event.getServer(), FMLPaths.CONFIGDIR.get());
    }

    @SubscribeEvent
    static void serverStopped(ServerStoppedEvent event) {
        MinecraftServerLifecycle.stopped(event.getServer());
    }

    @SubscribeEvent
    static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MinecraftServerLifecycle.connected(player.level().getServer(), player);
        }
    }

    @SubscribeEvent
    static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MinecraftServerLifecycle.disconnected(player.level().getServer(), player);
        }
    }
}
