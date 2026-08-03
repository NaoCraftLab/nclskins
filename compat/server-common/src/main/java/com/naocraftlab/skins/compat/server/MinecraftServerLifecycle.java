package com.naocraftlab.skins.compat.server;

import com.naocraftlab.skins.compat.server.MinecraftServerRefreshConfig.ConfigException;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;


public final class MinecraftServerLifecycle {
    private MinecraftServerLifecycle() {}

    public static void started(MinecraftServer server, Path configDirectory) {
        try {
            MinecraftServerAppearanceService.register(
                    Objects.requireNonNull(server, "server"),
                    Objects.requireNonNull(configDirectory, "configDirectory"));
        } catch (ConfigException invalidConfig) {
            throw new IllegalStateException(
                    "NCL Skins server configuration is invalid", invalidConfig);
        }
    }

    public static void stopped(MinecraftServer server) {
        MinecraftServerAppearanceService.closeRegistered(
                Objects.requireNonNull(server, "server"));
    }

    public static void connected(MinecraftServer server, ServerPlayer player) {
        MinecraftServer checkedServer = Objects.requireNonNull(server, "server");
        ServerPlayer checkedPlayer = Objects.requireNonNull(player, "player");
        MinecraftServerAppearanceService.registered(checkedServer)
                .ifPresent(service -> service.connected(checkedPlayer));
    }

    public static void disconnected(MinecraftServer server, ServerPlayer player) {
        MinecraftServer checkedServer = Objects.requireNonNull(server, "server");
        ServerPlayer checkedPlayer = Objects.requireNonNull(player, "player");
        MinecraftServerAppearanceService.registered(checkedServer)
                .ifPresent(service -> service.disconnected(checkedPlayer));
    }

    public static void disconnected(
            MinecraftServer server, ServerGamePacketListenerImpl listener) {
        MinecraftServer checkedServer = Objects.requireNonNull(server, "server");
        ServerGamePacketListenerImpl checkedListener =
                Objects.requireNonNull(listener, "listener");
        MinecraftServerAppearanceService.registered(checkedServer)
                .ifPresent(service -> service.disconnected(checkedListener));
    }
}
