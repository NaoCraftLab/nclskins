package com.naocraftlab.skins.compat.client;

import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.compat.server.MinecraftAppearanceRefreshNetwork;
import io.netty.buffer.Unpooled;
import java.util.OptionalLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;


public final class MinecraftServerAppearanceRefreshNotifier
        implements ServerAppearanceRefreshNotifier {
    private ClientPacketListener observedConnection;
    private long connectionGeneration;

    @Override
    public OptionalLong activeConnectionGeneration() {
        ClientPacketListener connection = currentConnection();
        return connection == null
                ? OptionalLong.empty()
                : OptionalLong.of(connectionGeneration);
    }

    @Override
    public void requestOfficialProfileRefresh() {
        ClientPacketListener connection = currentConnection();
        if (connection == null) {
            return;
        }
        connection.getConnection().send(new ServerboundCustomPayloadPacket(
                MinecraftAppearanceRefreshNetwork.ID,
                new FriendlyByteBuf(Unpooled.buffer(0, 0))));
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
