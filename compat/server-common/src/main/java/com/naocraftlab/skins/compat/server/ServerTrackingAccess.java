package com.naocraftlab.skins.compat.server;

import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;


interface ServerTrackingAccess {
    Optional<PlayerTracking> tracking(ServerPlayer actor);

    void scheduleNextTick(MinecraftServer server, Runnable action);
}
