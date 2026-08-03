package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.compat.server.MinecraftServerLifecycle;
import com.naocraftlab.skins.compat.server.MinecraftServerRefreshCommand;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;


final class FabricServerBridge {
    private FabricServerBridge() {}

    static void install() {
        CommandRegistrationCallback.EVENT.register((dispatcher, ignoredRegistry, ignoredEnvironment) ->
                MinecraftServerRefreshCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                MinecraftServerLifecycle.started(
                        server, FabricLoader.getInstance().getConfigDir()));
        ServerLifecycleEvents.SERVER_STOPPED.register(
                MinecraftServerLifecycle::stopped);
        ServerPlayConnectionEvents.JOIN.register((handler, ignoredSender, server) ->
                MinecraftServerLifecycle.connected(server, handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                MinecraftServerLifecycle.disconnected(server, handler));
    }
}
