package com.naocraftlab.skins.compat.server;

import com.naocraftlab.skins.compat.network.AppearanceRefreshPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;


public final class MinecraftAppearanceRefreshNetwork {
    private MinecraftAppearanceRefreshNetwork() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playToServer(
                        AppearanceRefreshPayload.TYPE,
                        AppearanceRefreshPayload.CODEC,
                        MinecraftAppearanceRefreshNetwork::received);
    }

    private static void received(
            AppearanceRefreshPayload ignoredPayload,
            IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> MinecraftServerAppearanceService
                .registered(player.level().getServer())
                .ifPresent(service -> service.request(player)));
    }
}
