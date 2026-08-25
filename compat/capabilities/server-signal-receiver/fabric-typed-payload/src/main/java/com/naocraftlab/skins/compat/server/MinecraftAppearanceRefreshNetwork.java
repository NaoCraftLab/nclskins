package com.naocraftlab.skins.compat.server;

import com.naocraftlab.skins.compat.network.AppearanceRefreshPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;


public final class MinecraftAppearanceRefreshNetwork {
    private MinecraftAppearanceRefreshNetwork() {}

    public static void install() {
        PayloadTypeRegistry.playC2S().register(
                AppearanceRefreshPayload.TYPE,
                AppearanceRefreshPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                AppearanceRefreshPayload.TYPE,
                (ignoredPayload, context) ->
                        MinecraftServerAppearanceService.registered(context.server())
                                .ifPresent(service -> service.request(context.player())));
    }
}
