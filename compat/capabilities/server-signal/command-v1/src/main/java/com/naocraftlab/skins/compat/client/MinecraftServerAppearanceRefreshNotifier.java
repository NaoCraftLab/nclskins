package com.naocraftlab.skins.compat.client;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.arguments.StringArgumentType;
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
        return refreshCommand(connection) != null
                ? OptionalLong.of(connectionGeneration)
                : OptionalLong.empty();
    }

    @Override
    public void requestOfficialProfileRefresh() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        String command = refreshCommand(connection);
        if (command == null) {
            return;
        }

        connection.sendCommand(command);
    }

    private static String refreshCommand(ClientPacketListener connection) {
        if (connection == null) {
            return null;
        }
        CommandNode<?> root = connection.getCommands().getRoot()
                .getChild(ServerRefreshCommandProtocol.ROOT_COMMAND);
        CommandNode<?> refresh = root == null
                ? null
                : root.getChild(ServerRefreshCommandProtocol.REFRESH_COMMAND);
        if (ServerAppearanceRefreshCommandPath.isExactExecutableLeaf(
                root != null,
                root instanceof LiteralCommandNode<?>,
                refresh != null,
                refresh instanceof LiteralCommandNode<?>,
                refresh != null && refresh.getCommand() != null,
                refresh != null && !refresh.getChildren().isEmpty())) {
            return ServerRefreshCommandProtocol.COMMAND;
        }

        CommandNode<?> bukkitRoot = connection.getCommands().getRoot()
                .getChild(ServerRefreshCommandProtocol.BUKKIT_ROOT_COMMAND);
        CommandNode<?> arguments = bukkitRoot == null ? null : bukkitRoot.getChild("args");
        boolean greedyString = arguments instanceof ArgumentCommandNode<?, ?> argument
                && argument.getType() instanceof StringArgumentType string
                && string.getType() == StringArgumentType.StringType.GREEDY_PHRASE;
        return ServerAppearanceRefreshCommandPath.isExactBukkitWrapper(
                bukkitRoot != null,
                bukkitRoot instanceof LiteralCommandNode<?>,
                bukkitRoot != null && bukkitRoot.getCommand() != null,
                bukkitRoot == null ? 0 : bukkitRoot.getChildren().size(),
                arguments != null,
                arguments instanceof ArgumentCommandNode<?, ?>,
                greedyString,
                arguments != null && arguments.getCommand() != null,
                arguments != null && !arguments.getChildren().isEmpty())
                ? ServerRefreshCommandProtocol.BUKKIT_COMMAND
                : null;
    }
}
