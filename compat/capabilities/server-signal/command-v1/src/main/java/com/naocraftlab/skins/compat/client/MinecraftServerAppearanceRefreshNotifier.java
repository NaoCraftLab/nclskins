package com.naocraftlab.skins.compat.client;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.naocraftlab.skins.client.ServerAppearanceRefreshCommandPath;
import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.server.ServerRefreshCommandProtocol;
import java.util.OptionalLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;


public final class MinecraftServerAppearanceRefreshNotifier
        implements ServerAppearanceRefreshNotifier {
    private ClientPacketListener observedConnection;
    private long connectionGeneration;

    @Override
    public OptionalLong activeConnectionGeneration() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != observedConnection) {
            observedConnection = connection;
            connectionGeneration++;
        }
        return supportsRefresh(connection)
                ? OptionalLong.of(connectionGeneration)
                : OptionalLong.empty();
    }

    @Override
    public void requestOfficialProfileRefresh() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (!supportsRefresh(connection)) {
            return;
        }

        connection.sendCommand(ServerRefreshCommandProtocol.COMMAND);
    }

    private static boolean supportsRefresh(ClientPacketListener connection) {
        if (connection == null) {
            return false;
        }
        CommandNode<?> root = connection.getCommands().getRoot()
                .getChild(ServerRefreshCommandProtocol.ROOT_COMMAND);
        CommandNode<?> refresh = root == null
                ? null
                : root.getChild(ServerRefreshCommandProtocol.REFRESH_COMMAND);
        return ServerAppearanceRefreshCommandPath.isExactExecutableLeaf(
                root != null,
                root instanceof LiteralCommandNode<?>,
                refresh != null,
                refresh instanceof LiteralCommandNode<?>,
                refresh != null && refresh.getCommand() != null,
                refresh != null && !refresh.getChildren().isEmpty());
    }
}
