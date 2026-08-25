package com.naocraftlab.skins.compat.server;

import com.naocraftlab.skins.server.AppearanceRefreshSignalProtocol;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;


public final class MinecraftAppearanceRefreshNetwork {
    private static final ResourceLocation CHANNEL = new ResourceLocation(
            AppearanceRefreshSignalProtocol.NAMESPACE,
            AppearanceRefreshSignalProtocol.PATH);

    private MinecraftAppearanceRefreshNetwork() {}

    public static void install() {
        ServerPlayNetworking.registerGlobalReceiver(
                CHANNEL,
                (server, player, ignoredHandler, payload, ignoredSender) -> {
                    byte[] bytes = payload.readableBytes() == 0
                            ? AppearanceRefreshSignalProtocol.payload()
                            : new byte[] {1};
                    AppearanceRefreshSignalProtocol.dispatch(
                            AppearanceRefreshSignalProtocol.Direction.CLIENT_TO_SERVER,
                            bytes,
                            () -> server.execute(() -> MinecraftServerAppearanceService
                                    .registered(server)
                                    .ifPresent(service -> service.request(player))));
                });
    }
}
