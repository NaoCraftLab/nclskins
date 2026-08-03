package com.naocraftlab.skins.compat.server;

import java.util.List;
import java.util.Objects;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;


final class MinecraftPlayerInfoTransport implements NativePlayerInfoTransport {
    @Override
    public void removeProfiles(ServerPlayer recipient, List<ServerPlayer> actors) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(actors, "actors");
        recipient.connection.send(new ClientboundPlayerInfoRemovePacket(
                actors.stream().map(ServerPlayer::getUUID).toList()));
    }

    @Override
    public void initializeProfiles(ServerPlayer recipient, List<ServerPlayer> actors) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(actors, "actors");
        recipient.connection.send(
                ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(actors));
    }
}
