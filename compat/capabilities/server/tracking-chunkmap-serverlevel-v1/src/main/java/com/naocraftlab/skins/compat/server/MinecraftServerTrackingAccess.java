package com.naocraftlab.skins.compat.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;


final class MinecraftServerTrackingAccess implements ServerTrackingAccess {
    private final MinecraftServer server;

    MinecraftServerTrackingAccess(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public Optional<PlayerTracking> tracking(ServerPlayer actor) {
        Objects.requireNonNull(actor, "actor");
        ServerLevel level = actor.serverLevel();
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        ChunkMap.TrackedEntity tracked = chunkMap.entityMap.get(actor.getId());
        if (tracked == null) {
            return Optional.empty();
        }
        List<ServerPlayer> observers = new ArrayList<>();
        for (ServerPlayerConnection connection : tracked.seenBy) {
            ServerPlayer observer = connection.getPlayer();
            if (observer != null
                    && observer != actor
                    && server.getPlayerList().getPlayer(observer.getUUID()) == observer) {
                observers.add(observer);
            }
        }
        List<ServerPlayer> snapshot = List.copyOf(observers);
        return Optional.of(new PlayerTracking() {
            @Override
            public Collection<ServerPlayer> observers() {
                return snapshot;
            }

            @Override
            public void untrack(ServerPlayer observer) {
                tracked.removePlayer(observer);
            }

            @Override
            public void retrack(ServerPlayer observer) {
                if (server.getPlayerList().getPlayer(observer.getUUID()) == observer) {
                    tracked.updatePlayer(observer);
                }
            }
        });
    }

    @Override
    public void scheduleNextTick(MinecraftServer server, Runnable action) {
        Objects.requireNonNull(server, "server").tell(new TickTask(
                server.getTickCount() + 1, Objects.requireNonNull(action, "action")));
    }
}
