package com.naocraftlab.skins.compat.server;

import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.ConnectionSnapshot;
import com.naocraftlab.skins.server.IdentityAssurance;
import com.naocraftlab.skins.server.runtime.ConnectionGenerationRegistry;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;


final class MinecraftServerConnectionRegistry implements AutoCloseable {
    private final MinecraftServer server;
    private final ConnectionGenerationRegistry<ServerGamePacketListenerImpl> generations =
            new ConnectionGenerationRegistry<>();
    private boolean closed;

    MinecraftServerConnectionRegistry(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    ConnectionRegistration connected(ServerPlayer player, IdentityAssurance assurance) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(assurance, "assurance");
        if (closed) {
            throw new IllegalStateException("Connection registry is closed");
        }
        ServerGamePacketListenerImpl listener =
                Objects.requireNonNull(player.connection, "player.connection");
        if (listener.player != player) {
            throw new IllegalArgumentException("Player does not own its native connection");
        }


        ConnectionGenerationRegistry.Registration registration = generations.connected(
                listener,
                player.getUUID(),
                player.getName().getString(),
                assurance);
        return new ConnectionRegistration(
                registration.snapshot(), registration.superseded());
    }

    Optional<ConnectionKey> disconnected(ServerGamePacketListenerImpl listener) {
        requireServerThread();
        Objects.requireNonNull(listener, "listener");
        return generations.disconnected(listener);
    }

    Optional<ConnectionKey> disconnected(ServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        ServerGamePacketListenerImpl listener = player.connection;
        return listener == null ? Optional.empty() : disconnected(listener);
    }

    ServerPlayer resolve(ConnectionKey key) {
        requireServerThread();
        Objects.requireNonNull(key, "key");
        if (closed) {
            return null;
        }
        ConnectionSnapshot snapshot = generations.snapshot(key).orElse(null);
        if (snapshot == null) {
            return null;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(key.profileId());
        if (player == null
                || player.connection == null
                || player.connection.player != player
                || !generations.matches(key, player.connection)
                || !snapshot.profileName().equals(player.getName().getString())) {
            return null;
        }
        return player;
    }

    Optional<ConnectionKey> keyFor(ServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        ServerGamePacketListenerImpl listener = player.connection;
        if (listener == null
                || listener.player != player
                || server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return Optional.empty();
        }
        ConnectionKey key = generations.keyFor(listener).orElse(null);
        if (key == null || !key.profileId().equals(player.getUUID())) {
            return Optional.empty();
        }
        ConnectionSnapshot snapshot = generations.snapshot(key).orElse(null);
        return snapshot != null && snapshot.profileName().equals(player.getName().getString())
                ? Optional.of(key)
                : Optional.empty();
    }

    @Override
    public void close() {
        requireServerThread();
        closed = true;
        generations.close();
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Connection registry access must run on the server thread");
        }
    }

    record ConnectionRegistration(
            ConnectionSnapshot snapshot,
            Optional<ConnectionKey> superseded) {
        ConnectionRegistration {
            Objects.requireNonNull(snapshot, "snapshot");
            superseded = Objects.requireNonNull(superseded, "superseded");
        }
    }
}
