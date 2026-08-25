package com.naocraftlab.skins.compat.client;

import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.server.AppearanceRefreshSignalProtocol;
import java.util.OptionalLong;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceLocation;


public final class MinecraftServerAppearanceRefreshNotifier
        implements ServerAppearanceRefreshNotifier {
    private static final ResourceLocation CHANNEL = new ResourceLocation(
            AppearanceRefreshSignalProtocol.NAMESPACE,
            AppearanceRefreshSignalProtocol.PATH);
    private ClientPacketListener observedConnection;
    private long connectionGeneration;

    @Override
    public OptionalLong activeConnectionGeneration() {
        ClientPacketListener connection = currentConnection();
        return connection != null && ClientPlayNetworking.canSend(CHANNEL)
                ? OptionalLong.of(connectionGeneration)
                : OptionalLong.empty();
    }

    @Override
    public void requestOfficialProfileRefresh() {
        ClientPacketListener connection = currentConnection();
        if (connection != null && ClientPlayNetworking.canSend(CHANNEL)) {
            ClientPlayNetworking.send(CHANNEL, PacketByteBufs.empty());
        }
    }

    private ClientPacketListener currentConnection() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != observedConnection) {
            observedConnection = connection;
            connectionGeneration++;
        }
        return connection;
    }
}
