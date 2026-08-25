package com.naocraftlab.skins.compat.server;

import com.naocraftlab.skins.server.AppearanceRefreshSignalProtocol;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;


public final class MinecraftAppearanceRefreshNetwork {
    public static final ResourceLocation ID = new ResourceLocation(
            AppearanceRefreshSignalProtocol.NAMESPACE,
            AppearanceRefreshSignalProtocol.PATH);
    public static final EventNetworkChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(ID)
            .networkProtocolVersion(() -> "1")
            .clientAcceptedVersions(NetworkRegistry.acceptMissingOr("1"))
            .serverAcceptedVersions(NetworkRegistry.acceptMissingOr("1"))
            .eventNetworkChannel();

    private MinecraftAppearanceRefreshNetwork() {}

    public static void install() {
        CHANNEL.addListener(MinecraftAppearanceRefreshNetwork::received);
    }

    private static void received(NetworkEvent.ClientCustomPayloadEvent event) {
        NetworkEvent.Context context = event.getSource().get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }
        byte[] payload = event.getPayload().readableBytes() == 0
                ? AppearanceRefreshSignalProtocol.payload()
                : new byte[] {1};
        AppearanceRefreshSignalProtocol.dispatch(
                AppearanceRefreshSignalProtocol.Direction.CLIENT_TO_SERVER,
                payload,
                () -> context.enqueueWork(() -> MinecraftServerAppearanceService
                        .registered(player.getServer())
                        .ifPresent(service -> service.request(player))));
        context.setPacketHandled(true);
    }
}
