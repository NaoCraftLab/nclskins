package com.naocraftlab.skins.compat.server;

import java.util.Collection;
import net.minecraft.server.level.ServerPlayer;


interface PlayerTracking {
    Collection<ServerPlayer> observers();

    void untrack(ServerPlayer observer);

    void retrack(ServerPlayer observer);
}
