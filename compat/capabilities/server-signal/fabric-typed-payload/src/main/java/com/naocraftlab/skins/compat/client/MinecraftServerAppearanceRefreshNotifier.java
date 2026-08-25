package com.naocraftlab.skins.compat.client;

import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.compat.network.AppearanceRefreshPayload;
import java.util.OptionalLong;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;


public final class MinecraftServerAppearanceRefreshNotifier
        implements ServerAppearanceRefreshNotifier {
    private ClientPacketListener observedConnection;
    private long connectionGeneration;

    @Override
    public OptionalLong activeConnectionGeneration() {
        ClientPacketListener connection = currentConnection();
        return connection != null && ClientPlayNetworking.canSend(AppearanceRefreshPayload.TYPE)
                ? OptionalLong.of(connectionGeneration)
                : OptionalLong.empty();
    }

    @Override
    public void requestOfficialProfileRefresh() {
        ClientPacketListener connection = currentConnection();
        if (connection != null && ClientPlayNetworking.canSend(AppearanceRefreshPayload.TYPE)) {
            ClientPlayNetworking.send(AppearanceRefreshPayload.INSTANCE);
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
