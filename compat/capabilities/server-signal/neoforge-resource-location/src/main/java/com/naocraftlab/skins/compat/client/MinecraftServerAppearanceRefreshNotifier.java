package com.naocraftlab.skins.compat.client;

import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.compat.network.AppearanceRefreshPayload;
import java.util.OptionalLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;


public final class MinecraftServerAppearanceRefreshNotifier
        implements ServerAppearanceRefreshNotifier {
    private ClientPacketListener observedConnection;
    private long connectionGeneration;

    @Override
    public OptionalLong activeConnectionGeneration() {
        ClientPacketListener connection = currentConnection();
        return connection != null && NetworkRegistry.hasChannel(
                connection, AppearanceRefreshPayload.TYPE.id())
                ? OptionalLong.of(connectionGeneration)
                : OptionalLong.empty();
    }

    @Override
    public void requestOfficialProfileRefresh() {
        ClientPacketListener connection = currentConnection();
        if (connection != null && NetworkRegistry.hasChannel(
                connection, AppearanceRefreshPayload.TYPE.id())) {
            PacketDistributor.sendToServer(AppearanceRefreshPayload.INSTANCE);
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
